package com.emporia.authentication.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
    private static final String DISABLED_ACCOUNT_ALERT = """
            <div class="alert alert-error">
              <span>Your account is disabled. Contact an administrator before signing in again.</span>
            </div>
            """;
    private static final String INVALID_CREDENTIALS_ALERT = """
            <div class="alert alert-error">
              <span>Invalid username or password. Please try again.</span>
            </div>
            """;

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "disabled", required = false) String disabled,
                            @RequestParam(value = "logout", required = false) String logout,
                            HttpServletRequest request) {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String csrfInput = csrfToken != null
                ? "<input type=\"hidden\" name=\"" + csrfToken.getParameterName() + "\" value=\"" + csrfToken.getToken() + "\"/>"
                : "";

        String errorAlert = "";
        if (disabled != null) {
            errorAlert = DISABLED_ACCOUNT_ALERT;
        } else if (error != null) {
            errorAlert = INVALID_CREDENTIALS_ALERT;
        }

        String logoutAlert = logout != null
                ? "<div class=\"alert alert-success\"><span>You have been signed out successfully.</span></div>"
                : "";

        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Emporia — Sign In</title>
                <style>
                    :root {
                        --night: #102b23;
                        --night-deep: #0a1f19;
                        --night-card: #13382d;
                        --green: #168d62;
                        --green-bright: #75d9a9;
                        --lime: #c6ef8b;
                        --red: #eb6b66;
                        --red-bg: rgba(204, 92, 88, 0.15);
                        --ink: #eaf4ed;
                        --muted: rgba(234, 244, 237, 0.6);
                        --line: rgba(117, 217, 169, 0.18);
                        --serif: Iowan Old Style, Baskerville, "Times New Roman", serif;
                    }

                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                    }

                    body {
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background:
                            radial-gradient(circle at 50% 15%, rgba(117, 217, 169, 0.15), transparent 30rem),
                            radial-gradient(circle at 85% 85%, rgba(198, 239, 139, 0.08), transparent 25rem),
                            linear-gradient(135deg, #0d261f 0%, var(--night-deep) 60%, #061510 100%);
                        color: var(--ink);
                        padding: 24px;
                    }

                    .login-card {
                        width: 100%;
                        max-width: 440px;
                        padding: 40px 36px;
                        background: rgba(16, 43, 35, 0.85);
                        border: 1px solid var(--line);
                        border-radius: 20px;
                        box-shadow: 0 28px 65px rgba(0, 0, 0, 0.45);
                        backdrop-filter: blur(16px);
                        -webkit-backdrop-filter: blur(16px);
                    }

                    .brand-header {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        margin-bottom: 28px;
                    }

                    .brand-mark {
                        display: grid;
                        width: 40px;
                        height: 40px;
                        place-items: center;
                        border-radius: 10px;
                        color: var(--night-deep);
                        background: var(--lime);
                        box-shadow: 0 0 20px rgba(198, 239, 139, 0.3);
                    }

                    .brand-mark svg {
                        width: 24px;
                        height: 24px;
                        fill: none;
                        stroke: currentColor;
                        stroke-linecap: round;
                        stroke-linejoin: round;
                        stroke-width: 2;
                    }

                    .brand-title {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-size: 1.35rem;
                        font-weight: 800;
                        letter-spacing: -0.03em;
                        color: var(--ink);
                    }

                    .brand-title small {
                        padding: 3px 7px;
                        border-radius: 4px;
                        color: var(--lime);
                        background: rgba(198, 239, 139, 0.12);
                        border: 1px solid rgba(198, 239, 139, 0.25);
                        font-size: 0.6rem;
                        font-weight: 800;
                        letter-spacing: 0.1em;
                        text-transform: uppercase;
                    }

                    .login-heading {
                        margin-bottom: 24px;
                    }

                    .login-heading h1 {
                        font-family: var(--serif);
                        font-size: 2rem;
                        font-weight: 500;
                        letter-spacing: -0.04em;
                        color: #ffffff;
                        margin-bottom: 6px;
                    }

                    .login-heading p {
                        font-size: 0.85rem;
                        color: var(--muted);
                        line-height: 1.45;
                    }

                    .alert {
                        padding: 12px 16px;
                        border-radius: 10px;
                        font-size: 0.82rem;
                        margin-bottom: 20px;
                        display: flex;
                        align-items: center;
                        gap: 10px;
                    }

                    .alert-error {
                        background: var(--red-bg);
                        border: 1px solid rgba(235, 107, 102, 0.3);
                        color: #ff9b97;
                    }

                    .alert-success {
                        background: rgba(117, 217, 169, 0.12);
                        border: 1px solid rgba(117, 217, 169, 0.3);
                        color: var(--green-bright);
                    }

                    .form-group {
                        margin-bottom: 20px;
                    }

                    .form-label {
                        display: block;
                        font-size: 0.72rem;
                        font-weight: 750;
                        letter-spacing: 0.08em;
                        text-transform: uppercase;
                        color: var(--muted);
                        margin-bottom: 8px;
                    }

                    .form-input {
                        width: 100%;
                        height: 48px;
                        padding: 0 16px;
                        background: rgba(10, 31, 25, 0.6);
                        border: 1px solid var(--line);
                        border-radius: 10px;
                        color: #ffffff;
                        font-size: 0.95rem;
                        transition: all 180ms ease;
                        outline: none;
                    }

                    .form-input:focus {
                        border-color: var(--green-bright);
                        box-shadow: 0 0 0 3px rgba(117, 217, 169, 0.2);
                        background: rgba(10, 31, 25, 0.85);
                    }

                    .submit-btn {
                        width: 100%;
                        height: 50px;
                        margin-top: 8px;
                        background: var(--lime);
                        color: var(--night-deep);
                        border: 1px solid var(--lime);
                        border-radius: 10px;
                        font-size: 0.9rem;
                        font-weight: 800;
                        letter-spacing: 0.03em;
                        cursor: pointer;
                        transition: all 180ms ease;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        gap: 10px;
                    }

                    .submit-btn:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 12px 28px rgba(198, 239, 139, 0.25);
                        background: #d4f79e;
                    }

                    .submit-btn:active {
                        transform: translateY(0);
                    }

                    .credential-hint {
                        margin-top: 24px;
                        padding: 14px 16px;
                        background: rgba(255, 255, 255, 0.03);
                        border: 1px dashed rgba(255, 255, 255, 0.12);
                        border-radius: 10px;
                        font-size: 0.78rem;
                        color: var(--muted);
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                    }

                    .credential-hint code {
                        color: var(--lime);
                        background: rgba(198, 239, 139, 0.1);
                        padding: 3px 7px;
                        border-radius: 5px;
                        font-family: SFMono-Regular, Consolas, "Liberation Mono", Menlo, monospace;
                        font-size: 0.78rem;
                    }

                    .footer-note {
                        margin-top: 28px;
                        text-align: center;
                        font-size: 0.7rem;
                        color: rgba(234, 244, 237, 0.35);
                    }
                </style>
            </head>
            <body>
                <div class="login-card">
                    <div class="brand-header">
                        <div class="brand-mark">
                            <svg viewBox="0 0 32 32">
                                <path d="M7 23V17M13 23V11M19 23V14M25 23V7"></path>
                                <path d="m6 12 7-5 6 3 7-6"></path>
                            </svg>
                        </div>
                        <div class="brand-title">
                            Emporia <small>Trade</small>
                        </div>
                    </div>

                    <div class="login-heading">
                        <h1>Welcome back</h1>
                        <p>Sign in to your account to open your trading desk.</p>
                    </div>

                    <!-- ERROR_ALERT -->
                    <!-- LOGOUT_ALERT -->

                    <form action="/login" method="post">
                        <!-- CSRF_INPUT -->

                        <div class="form-group">
                            <label class="form-label" for="username">Username</label>
                            <input class="form-input" type="text" id="username" name="username" placeholder="admin" required autofocus autocomplete="username">
                        </div>

                        <div class="form-group">
                            <label class="form-label" for="password">Password</label>
                            <input class="form-input" type="password" id="password" name="password" placeholder="••••••••" required autocomplete="current-password">
                        </div>

                        <button class="submit-btn" type="submit">
                            Sign in to Emporia
                            <svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <path d="M4 10h11M11 5l5 5-5 5"></path>
                            </svg>
                        </button>
                    </form>

                    <div class="credential-hint">
                        <span>Default admin login:</span>
                        <code>admin / admin123</code>
                    </div>

                    <div class="footer-note">
                        Protected by Emporia OAuth2 Authorization Server
                    </div>
                </div>
            </body>
            </html>
            """;

        return html
                .replace("<!-- ERROR_ALERT -->", errorAlert)
                .replace("<!-- LOGOUT_ALERT -->", logoutAlert)
                .replace("<!-- CSRF_INPUT -->", csrfInput);
    }
}
