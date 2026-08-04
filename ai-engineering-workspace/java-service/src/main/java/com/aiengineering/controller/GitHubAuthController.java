package com.aiengineering.controller;

import com.aiengineering.dto.AuthResponse;
import com.aiengineering.dto.GitHubTokenResponse;
import com.aiengineering.dto.GitHubUserProfile;
import com.aiengineering.entity.User;
import com.aiengineering.exception.UnauthorizedException;
import com.aiengineering.service.AuthService;
import com.aiengineering.service.GitHubOAuthService;
import com.aiengineering.service.LoginCodeService;
import com.aiengineering.util.TokenUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * The GitHub side of login. Flow:
 *
 *   1. Frontend redirects the browser to GET /auth/github/login
 *   2. We generate a random "state" value, stash it in a short-lived
 *      HttpOnly cookie, and redirect to GitHub's authorize page WITH that
 *      state included.
 *   3. User approves -> GitHub redirects back to
 *      /auth/github/callback?code=...&state=...
 *   4. We compare GitHub's returned state against our cookie. Mismatch or
 *      missing -> reject. This is the actual CSRF protection the state
 *      parameter exists for - generating it without checking it later (the
 *      previous version of this file) provides none of the protection.
 *   5. We exchange the code for a GitHub token, fetch the profile, upsert
 *      our User, store the encrypted GitHub token, and issue OUR OWN
 *      access + refresh tokens (see AuthService).
 *   6. Rather than putting those tokens in the redirect URL (they'd sit in
 *      browser history, server logs, proxy logs, Referer headers...), we
 *      hand them to LoginCodeService, which returns a short-lived, single-
 *      use opaque CODE. We redirect with only that code. The frontend then
 *      POSTs it to /auth/exchange to get the real tokens back in a JSON
 *      response body - never in a URL.
 */
@RestController
@RequestMapping("/auth/github")
public class GitHubAuthController {

    private static final String STATE_COOKIE_NAME = "oauth_state";
    private static final int STATE_COOKIE_MAX_AGE_SECONDS = 600; // 10 minutes - plenty for a login round trip

    private final GitHubOAuthService gitHubOAuthService;
    private final AuthService authService;
    private final LoginCodeService loginCodeService;
    private final String frontendUrl;
    private final boolean cookieSecure;

    public GitHubAuthController(
        GitHubOAuthService gitHubOAuthService,
        AuthService authService,
        LoginCodeService loginCodeService,
        @Value("${app.frontend-url}") String frontendUrl,
        @Value("${cookie.secure}") boolean cookieSecure
    ) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.authService = authService;
        this.loginCodeService = loginCodeService;
        this.frontendUrl = frontendUrl;
        this.cookieSecure = cookieSecure;
    }

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        String state = TokenUtil.generateOpaqueToken();

        Cookie stateCookie = new Cookie(STATE_COOKIE_NAME, state);
        stateCookie.setHttpOnly(true);
        stateCookie.setSecure(cookieSecure);
        stateCookie.setPath("/auth/github");
        stateCookie.setMaxAge(STATE_COOKIE_MAX_AGE_SECONDS);
        response.addCookie(stateCookie);

        response.sendRedirect(gitHubOAuthService.buildAuthorizationUrl(state));
    }

    @GetMapping("/callback")
    public void callback(
        @RequestParam("code") String code,
        @RequestParam(value = "state", required = false) String returnedState,
        @CookieValue(value = STATE_COOKIE_NAME, required = false) String expectedState,
        HttpServletResponse response
    ) throws IOException {

        // Clear the state cookie either way - it's single-use by nature of an
        // OAuth login round trip, no reason to let it linger.
        Cookie clearCookie = new Cookie(STATE_COOKIE_NAME, "");
        clearCookie.setPath("/auth/github");
        clearCookie.setMaxAge(0);
        response.addCookie(clearCookie);

        if (expectedState == null || returnedState == null || !TokenUtil.constantTimeEquals(expectedState, returnedState)) {
            throw new UnauthorizedException(
                "OAuth state mismatch - possible CSRF attempt, or the login session expired. Please try logging in again."
            );
        }

        GitHubTokenResponse tokenResponse = gitHubOAuthService.exchangeCodeForToken(code);
        GitHubUserProfile profile = gitHubOAuthService.fetchUserProfile(tokenResponse.accessToken());

        User user = gitHubOAuthService.upsertUser(profile);
        gitHubOAuthService.storeGitHubCredential(user, tokenResponse);

        AuthResponse authResponse = authService.login(user);

        // Hand off via a short-lived, single-use exchange code - never the
        // actual tokens - so nothing sensitive ends up sitting in this URL.
        String loginCode = loginCodeService.issueCode(authResponse);

        String redirectUrl = String.format("%s/auth/callback?code=%s", frontendUrl, loginCode);

        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader("Location", redirectUrl);
    }
}
