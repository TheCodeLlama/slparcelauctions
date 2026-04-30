package com.slparcelauctions.backend.sl;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Rewrites the {@code Content-Type} of {@link ProblemDetail} responses
 * served from any {@code /api/v1/sl/**} endpoint to
 * {@code application/json} instead of the RFC 9457 default
 * {@code application/problem+json}.
 *
 * <p><strong>Why:</strong> the in-world LSL HTTP layer
 * ({@code llHTTPRequest}) filters response Content-Types against its
 * {@code HTTP_ACCEPT} option. The default accepts only
 * {@code text/plain;charset=utf-8}; in practice the grid also passes
 * {@code application/json} through, but it does <em>not</em> recognise
 * the {@code +json} structured-syntax suffix. When a backend response
 * carries Content-Type {@code application/problem+json}, the SL grid
 * silently replaces the entire response with a synthetic {@code 415} +
 * body {@code "Unsupported or unknown Content-Type."} — the script
 * never sees the real status code or ProblemDetail body.
 *
 * <p>This advice runs after Spring's exception handlers serialise their
 * {@link ProblemDetail} but before the response is written, so we can
 * downgrade the Content-Type on the way out. Web clients (frontend,
 * Postman, anything not behind the SL grid) hit non-{@code /api/v1/sl}
 * paths and continue to receive RFC-compliant
 * {@code application/problem+json}.
 *
 * <p>The body itself is unchanged — clients reading the documented
 * ProblemDetail fields ({@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}, plus our {@code code} extension)
 * continue to work regardless of the Content-Type header.
 */
@RestControllerAdvice
public class SlProblemDetailContentTypeAdvice implements ResponseBodyAdvice<ProblemDetail> {

    private static final String SL_PATH_PREFIX = "/api/v1/sl/";

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return ProblemDetail.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public ProblemDetail beforeBodyWrite(ProblemDetail body,
                                         MethodParameter returnType,
                                         MediaType selectedContentType,
                                         Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                         ServerHttpRequest request,
                                         ServerHttpResponse response) {
        if (request.getURI().getPath().startsWith(SL_PATH_PREFIX)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }
        return body;
    }
}
