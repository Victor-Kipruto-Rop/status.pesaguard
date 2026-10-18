package com.pesaguard.backend.common.email;

import org.springframework.web.util.HtmlUtils;

/** Shared PesaGuard logo markup for transactional email templates. */
public final class EmailBranding {

    private static final String LOGO_URL = "https://pesaguard.co.ke/logo/logo.svg";

    private EmailBranding() {
    }

    public static String logoMarkup() {
        return """
                <img src="%s" width="184" alt="PesaGuard"
                     style="display:block;width:184px;max-width:100%%;height:auto;border:0;">
                """.formatted(LOGO_URL);
    }

    public static String brandedHtml(String subject, String text) {
        String escapedSubject = HtmlUtils.htmlEscape(subject == null ? "" : subject);
        String escapedText = HtmlUtils.htmlEscape(text == null ? "" : text)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "<br>");
        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="color-scheme" content="light">
                    <title>%s</title>
                  </head>
                  <body style="margin:0;padding:0;background:#f4f7f3;color:#26342a;font-family:Arial,Helvetica,sans-serif;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f4f7f3;">
                      <tr><td align="center" style="padding:42px 16px;">
                        <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;">
                          <tr><td style="padding:0 12px 20px;">%s</td></tr>
                          <tr><td style="padding:0;background:#fff;border:1px solid #e4eae3;border-radius:16px;overflow:hidden;">
                            <div style="height:5px;background:#2fa943;font-size:0;line-height:0;">&nbsp;</div>
                            <div style="padding:38px 42px 36px;">
                              <h1 style="margin:0 0 18px;font-size:28px;line-height:1.2;color:#202a23;">%s</h1>
                              <p style="margin:0;font-size:15px;line-height:1.7;color:#58635b;">%s</p>
                            </div>
                          </td></tr>
                          <tr><td align="center" style="padding:20px 18px 0;font-size:12px;line-height:1.7;color:#7a857d;">PesaGuard Developer Platform · support@pesaguard.co.ke</td></tr>
                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(escapedSubject, logoMarkup(), escapedSubject, escapedText);
    }
}
