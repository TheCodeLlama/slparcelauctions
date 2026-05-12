package com.slparcelauctions.backend.realty.slgroup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.auth.test.WithMockAuthPrincipal;
import com.slparcelauctions.backend.common.exception.GlobalExceptionHandler;
import com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler;
import com.slparcelauctions.backend.realty.exception.RealtyGroupPermissionDeniedException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDto;
import com.slparcelauctions.backend.realty.slgroup.dto.RealtyGroupSlGroupDtoMapper;
import com.slparcelauctions.backend.realty.slgroup.dto.RegisterSlGroupRequest;
import com.slparcelauctions.backend.realty.slgroup.exception.RegisteredSlGroupHasListingsException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupAlreadyRegisteredException;
import com.slparcelauctions.backend.realty.slgroup.exception.SlGroupRegisteredToSuspendedGroupException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Slice tests for {@link RealtyGroupSlGroupController}. Mirrors the
 * {@code ReviewControllerTest} shape: security filters disabled (auth wiring is
 * covered elsewhere), service + mapper mocked, and the realty + global exception
 * handlers imported so status / {@code code} extension assertions resolve.
 */
@WebMvcTest(controllers = RealtyGroupSlGroupController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, RealtyExceptionHandler.class})
class RealtyGroupSlGroupControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private RealtyGroupSlGroupService service;
    @MockitoBean private RealtyGroupSlGroupDtoMapper mapper;
    // RealtyExceptionHandler resolves UnsupportedImageFormatException from
    // user.exception which the slice loader otherwise tries to scan widely;
    // pulling these in keeps the slice context minimal.
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;

    private static final UUID GROUP_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SL_GROUP_UUID =
            UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID SL_GROUP_PUBLIC_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final long CALLER_USER_ID = 1L;

    private RealtyGroupSlGroupDto sampleDto(boolean verified) {
        return new RealtyGroupSlGroupDto(
                SL_GROUP_PUBLIC_ID,
                SL_GROUP_UUID,
                "Test SL Group",
                verified,
                verified ? OffsetDateTime.now() : null,
                verified ? SlGroupVerifyMethod.FOUNDER_TERMINAL : null,
                null,
                null);
    }

    // ─────────────── register ───────────────

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void register_200OnHappyPath() throws Exception {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder()
                .slGroupUuid(SL_GROUP_UUID)
                .build();
        when(service.register(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID), eq(SL_GROUP_UUID)))
                .thenReturn(row);
        when(mapper.toDto(any(RealtyGroupSlGroup.class))).thenReturn(sampleDto(false));

        RegisterSlGroupRequest req = new RegisterSlGroupRequest(SL_GROUP_UUID);
        mockMvc.perform(post("/api/v1/realty/groups/" + GROUP_PUBLIC_ID + "/sl-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(SL_GROUP_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.slGroupUuid").value(SL_GROUP_UUID.toString()))
                .andExpect(jsonPath("$.verified").value(false));
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void register_403WithoutPermission() throws Exception {
        when(service.register(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID), eq(SL_GROUP_UUID)))
                .thenThrow(new RealtyGroupPermissionDeniedException(
                        RealtyGroupPermission.REGISTER_SL_GROUP));

        RegisterSlGroupRequest req = new RegisterSlGroupRequest(SL_GROUP_UUID);
        mockMvc.perform(post("/api/v1/realty/groups/" + GROUP_PUBLIC_ID + "/sl-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REALTY_GROUP_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.missingPermission").value("REGISTER_SL_GROUP"));
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void register_409WhenAlreadyRegistered() throws Exception {
        when(service.register(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID), eq(SL_GROUP_UUID)))
                .thenThrow(new SlGroupAlreadyRegisteredException(SL_GROUP_UUID));

        RegisterSlGroupRequest req = new RegisterSlGroupRequest(SL_GROUP_UUID);
        mockMvc.perform(post("/api/v1/realty/groups/" + GROUP_PUBLIC_ID + "/sl-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SL_GROUP_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.slGroupUuid").value(SL_GROUP_UUID.toString()));
    }

    /**
     * Sub-project G section 14 -- the reverse-search gate maps to 409 with the
     * distinct {@code SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP} code and carries
     * the offending {@code slGroupUuid} in the body. Frontend surfaces a
     * "contact support" message.
     */
    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void register_409WhenRegisteredToSuspendedGroup() throws Exception {
        when(service.register(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID), eq(SL_GROUP_UUID)))
                .thenThrow(new SlGroupRegisteredToSuspendedGroupException(SL_GROUP_UUID));

        RegisterSlGroupRequest req = new RegisterSlGroupRequest(SL_GROUP_UUID);
        mockMvc.perform(post("/api/v1/realty/groups/" + GROUP_PUBLIC_ID + "/sl-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SL_GROUP_REGISTERED_TO_SUSPENDED_GROUP"))
                .andExpect(jsonPath("$.slGroupUuid").value(SL_GROUP_UUID.toString()));
    }

    // ─────────────── list ───────────────

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void list_200WithRows() throws Exception {
        RealtyGroupSlGroup row1 = RealtyGroupSlGroup.builder().build();
        RealtyGroupSlGroup row2 = RealtyGroupSlGroup.builder().build();
        when(service.listForGroup(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID)))
                .thenReturn(List.of(row1, row2));
        when(mapper.toDto(any(RealtyGroupSlGroup.class)))
                .thenReturn(sampleDto(true))
                .thenReturn(sampleDto(false));

        mockMvc.perform(get("/api/v1/realty/groups/" + GROUP_PUBLIC_ID + "/sl-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].verified").value(true))
                .andExpect(jsonPath("$[1].verified").value(false));
    }

    // ─────────────── unregister ───────────────

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void unregister_204OnHappyPath() throws Exception {
        mockMvc.perform(delete("/api/v1/realty/groups/" + GROUP_PUBLIC_ID
                        + "/sl-groups/" + SL_GROUP_PUBLIC_ID))
                .andExpect(status().isNoContent());
        verify(service).unregister(CALLER_USER_ID, GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID);
    }

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void unregister_409WhenActiveListings() throws Exception {
        doThrow(new RegisteredSlGroupHasListingsException(SL_GROUP_PUBLIC_ID))
                .when(service).unregister(CALLER_USER_ID, GROUP_PUBLIC_ID, SL_GROUP_PUBLIC_ID);

        mockMvc.perform(delete("/api/v1/realty/groups/" + GROUP_PUBLIC_ID
                        + "/sl-groups/" + SL_GROUP_PUBLIC_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTERED_SL_GROUP_HAS_LISTINGS"))
                .andExpect(jsonPath("$.slGroupPublicId").value(SL_GROUP_PUBLIC_ID.toString()));
    }

    // ─────────────── recheck ───────────────

    @Test
    @WithMockAuthPrincipal(userId = CALLER_USER_ID)
    void recheck_200OnNoOp() throws Exception {
        RealtyGroupSlGroup row = RealtyGroupSlGroup.builder().build();
        when(service.recheck(eq(CALLER_USER_ID), eq(GROUP_PUBLIC_ID), eq(SL_GROUP_PUBLIC_ID)))
                .thenReturn(row);
        when(mapper.toDto(any(RealtyGroupSlGroup.class))).thenReturn(sampleDto(true));

        mockMvc.perform(post("/api/v1/realty/groups/" + GROUP_PUBLIC_ID
                        + "/sl-groups/" + SL_GROUP_PUBLIC_ID + "/recheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(SL_GROUP_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.verified").value(true));
    }
}
