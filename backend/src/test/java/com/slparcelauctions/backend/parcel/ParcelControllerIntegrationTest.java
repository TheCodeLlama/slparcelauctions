package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.parcel.dto.ParcelLookupRequest;
import com.slparcelauctions.backend.sl.ParcelIngestException;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;

import reactor.core.publisher.Mono;

/**
 * Integration-style coverage for {@code POST /api/v1/parcels/lookup}.
 *
 * <p>Uses {@code @SpringBootTest} so the full Spring Security filter chain,
 * JWT auth filter, and {@code GlobalExceptionHandler} all run. The external
 * SL HTTP clients ({@link SlWorldApiClient}, {@link SlMapApiClient}) are
 * mocked via {@code @MockitoBean} so no outbound network calls happen.
 *
 * <p>User states covered:
 * <ul>
 *   <li>Unauthenticated -> 401 from {@code JwtAuthenticationEntryPoint}.</li>
 *   <li>Authenticated, unverified -> 403 via {@code NotVerifiedException}.</li>
 *   <li>Authenticated, verified -> success paths and domain error paths.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@Transactional
class ParcelControllerIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean SlWorldApiClient worldApi;
    /** Unverified user: returned from register flow as-is. */
    private String unverifiedAccessToken;

    /** Verified user: ran through /sl/verify to set verified=true. */
    private String verifiedAccessToken;

    @BeforeEach
    void setUp() throws Exception {
        unverifiedAccessToken = registerUser("parcel-unverified@example.com", "Unverified");
        verifiedAccessToken = registerAndVerifyUser(
                "parcel-verified@example.com", "Verified",
                "dddddddd-dddd-dddd-dddd-dddddddddddd");
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void lookup_newMainlandUuid_returns200AndPersistsParcel() throws Exception {
        UUID parcelUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        stubMainlandMetadata(parcelUuid, ownerUuid, "Coniston", 260000.0, 254000.0);

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slParcelUuid").value(parcelUuid.toString()))
                .andExpect(jsonPath("$.regionName").value("Coniston"))
                .andExpect(jsonPath("$.continentName").value("Sansara"))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.slurl").value(org.hamcrest.Matchers.containsString("Coniston")))
                // Detail-page VisitInSecondLifeBlock reads these off the DTO.
                .andExpect(jsonPath("$.positionX").value(128.0))
                .andExpect(jsonPath("$.positionY").value(64.0))
                .andExpect(jsonPath("$.positionZ").value(22.0));

        assertThat(parcelRepository.findBySlParcelUuid(parcelUuid)).isPresent();
    }

    @Test
    void lookup_sameUuidTwice_secondCallIsCacheHit() throws Exception {
        UUID parcelUuid = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID ownerUuid = UUID.fromString("66666666-6666-6666-6666-666666666666");
        stubMainlandMetadata(parcelUuid, ownerUuid, "Coniston", 260000.0, 254000.0);

        String body = objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        // Second call must not hit external APIs
        verify(worldApi, times(1)).fetchParcelPage(parcelUuid);
        verify(worldApi, times(1)).fetchRegionPage(org.mockito.ArgumentMatchers.any(java.util.UUID.class));
        assertThat(parcelRepository.findBySlParcelUuid(parcelUuid)).isPresent();
    }

    // -------------------------------------------------------------------------
    // Domain error paths (service throws, GlobalExceptionHandler maps)
    // -------------------------------------------------------------------------

    @Test
    void lookup_worldApiParserFailure_returns422() throws Exception {
        // World API returned HTML the parser couldn't extract a required field
        // from. Surfaced as a clean 422 PARCEL_INGEST_FAILED rather than
        // bubbling up to the catch-all 500 — defense in depth in case SL
        // changes the page shape again.
        UUID parcelUuid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.error(new ParcelIngestException("required meta missing")));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARCEL_INGEST_FAILED"));
    }

    @Test
    void lookup_nonMainlandCoords_returns422() throws Exception {
        UUID parcelUuid = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID ownerUuid = UUID.randomUUID();
        // Region resolves to coords that fall outside every Mainland bounding box.
        stubMainlandMetadata(parcelUuid, ownerUuid, "PrivateEstate", 100000.0, 100000.0);

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_MAINLAND"));
    }

    // -------------------------------------------------------------------------
    // Request validation (Jackson layer)
    // -------------------------------------------------------------------------

    @Test
    void lookup_malformedUuid_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + verifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // -------------------------------------------------------------------------
    // Auth paths
    // -------------------------------------------------------------------------

    @Test
    void lookup_unauthenticated_returns401() throws Exception {
        UUID parcelUuid = UUID.randomUUID();
        // NOTE: JwtAuthenticationEntryPoint serializes its ProblemDetail via a bare
        // ObjectMapper (see FOOTGUNS B.2) which nests extension properties under the
        // "properties" key rather than flattening them. GlobalExceptionHandler uses
        // Spring's ProblemDetail Jackson mixin which flattens them. Both shapes are
        // valid RFC 9457 — asserting against the actual entry-point shape here.
        mockMvc.perform(post("/api/v1/parcels/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.properties.code").value("AUTH_TOKEN_MISSING"));
    }

    @Test
    void lookup_authenticatedButUnverified_returns403() throws Exception {
        UUID parcelUuid = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + unverifiedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ParcelLookupRequest(parcelUuid))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VERIFIED"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"%s\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(reg.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String registerAndVerifyUser(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);

        // Generate verification code
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();

        // Fire /sl/verify (trusted SL headers). Sets user.verified = true.
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"%s",
              "avatarName":"%s",
              "displayName":"%s",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code, avatarUuid, displayName, displayName);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());

        return token;
    }

    private void stubMainlandMetadata(UUID parcelUuid, UUID ownerUuid, String regionName,
                                      double gridX, double gridY) {
        UUID regionUuid = UUID.randomUUID();
        when(worldApi.fetchParcelPage(parcelUuid)).thenReturn(
                Mono.just(new ParcelPageData(new ParcelMetadata(
                        parcelUuid,
                ownerUuid,
                "agent",
                null,
                "Test Parcel",
                regionName,
                1024,
                "Test description",
                "http://example.com/snap.jpg",
                null,
                128.0,
                64.0,
                22.0), regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(
                Mono.just(new RegionPageData(regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT")));
    }
}
