package com.photographer.focusstack.app.security;

import com.photographer.focusstack.app.api.ApiPaths;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Exchanges an authenticated browser request for an HttpOnly session usable by the plain WebSocket. */
@Controller
@RequestMapping({ApiPaths.V1 + "/browser/socket-session", ApiPaths.LEGACY + "/browser/socket-session"})
public class BrowserSocketSession {
    private static final String ATTRIBUTE = BrowserSocketSession.class.getName() + ".CONTEXT";
    private final PlainWebSocketBridge bridge;

    public BrowserSocketSession(PlainWebSocketBridge bridge) {
        this.bridge = bridge;
    }
    private static final String AJAX = "X-Requested-With=XMLHttpRequest";

    private static HttpSessionSecurityContextRepository repository() {
        var repository = new HttpSessionSecurityContextRepository();
        repository.setSpringSecurityContextKey(ATTRIBUTE);
        return repository;
    }

    static SecurityContextRepository handshakeRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), repository(),
                new HttpSessionSecurityContextRepository());
    }

    // The custom header prevents a cross-origin HTML form from creating or revoking this session.
    @PostMapping(headers = AJAX)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(HttpServletRequest request, HttpServletResponse response) {
        var previous = request.getSession(false);
        if (previous != null) {
            bridge.closeBrowserSession(previous.getId());
            request.changeSessionId();
        }
        repository().saveContext(SecurityContextHolder.getContext(), request, response);
    }

    @DeleteMapping(headers = AJAX)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
            bridge.closeBrowserSession(session.getId());
        }
    }
}
