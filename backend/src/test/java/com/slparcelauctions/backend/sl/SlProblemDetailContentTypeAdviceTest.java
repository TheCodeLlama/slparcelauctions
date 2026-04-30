package com.slparcelauctions.backend.sl;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.JwtAuthenticationEntryPoint;
import com.slparcelauctions.backend.auth.JwtAuthenticationFilter;
import com.slparcelauctions.backend.config.SecurityConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that {@link SlProblemDetailContentTypeAdvice} downgrades the
 * {@code application/problem+json} default Content-Type to
 * {@code application/json} on {@code /api/v1/sl/**} paths only. Web
 * frontends (and every other client) hitting any other path continue to
 * see the RFC 9457 default.
 *
 * <p>Uses two minimal stub controllers (one SL-pathed, one not) instead
 * of a real domain controller so the test isolates the advice's path
 * check from any specific endpoint's behaviour.
 */
@WebMvcTest(
        controllers = {
                SlProblemDetailContentTypeAdviceTest.SlPathStubController.class,
                SlProblemDetailContentTypeAdviceTest.NonSlPathStubController.class
        },
        excludeAutoConfiguration = {
                SecurityAutoConfiguration.class,
                SecurityFilterAutoConfiguration.class,
                UserDetailsServiceAutoConfiguration.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SecurityConfig.class,
                           JwtAuthenticationFilter.class,
                           JwtAuthenticationEntryPoint.class}))
@AutoConfigureMockMvc(addFilters = false)
@Import({SlProblemDetailContentTypeAdvice.class,
        SlProblemDetailContentTypeAdviceTest.SlPathStubController.class,
        SlProblemDetailContentTypeAdviceTest.NonSlPathStubController.class})
class SlProblemDetailContentTypeAdviceTest {

    @Autowired MockMvc mockMvc;

    @Test
    void slPath_problemDetail_servedAsApplicationJson() throws Exception {
        mockMvc.perform(get("/api/v1/sl/stub-throw"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("Stub failure"));
    }

    @Test
    void nonSlPath_problemDetail_servedAsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/elsewhere/stub-throw"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Stub failure"));
    }

    // -----------------------------------------------------------------
    // Stub fixtures: two endpoints (SL-pathed and not) returning a
    // ProblemDetail directly. The advice runs after the handler-method
    // return value is materialised, so this exercises the same code
    // path as a real @ExceptionHandler producing a ProblemDetail.
    // -----------------------------------------------------------------

    private static ProblemDetail stubProblemDetail() {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "stub detail");
        pd.setTitle("Stub failure");
        return pd;
    }

    @RestController
    @RequestMapping("/api/v1/sl")
    static class SlPathStubController {
        @GetMapping("/stub-throw")
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        ProblemDetail stub() {
            return stubProblemDetail();
        }
    }

    @RestController
    @RequestMapping("/api/v1/elsewhere")
    static class NonSlPathStubController {
        @GetMapping("/stub-throw")
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        ProblemDetail stub() {
            return stubProblemDetail();
        }
    }
}
