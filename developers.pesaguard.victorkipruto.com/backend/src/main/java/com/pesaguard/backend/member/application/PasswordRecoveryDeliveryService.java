package com.pesaguard.backend.member.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.pesaguard.backend.common.email.EmailBranding;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Delivers password reset links without logging token material. */
@Service
public class PasswordRecoveryDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryDeliveryService.class);
    private static final String SUPPORT_ADDRESS = "support@pesaguard.co.ke";
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String resetUrl;

    public PasswordRecoveryDeliveryService(
            JavaMailSender mailSender,
            @Value("${pesaguard.identity.from-email}") String fromAddress,
            @Value("${pesaguard.identity.verification-url}") String portalUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.resetUrl = portalUrl.replaceAll("/+$", "") + "/reset-password";
    }

    public void send(String email, String token) {
        String resetLink = resetUrl + "#reset-password="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setReplyTo(SUPPORT_ADDRESS);
            helper.setSubject("Reset your PesaGuard password");
            helper.setText(plainText(resetLink), htmlContent(HtmlUtils.htmlEscape(resetLink)));
            mailSender.send(message);
        } catch (MessagingException | MailException exception) {
            log.error("Password reset delivery failed ({})", exception.getClass().getSimpleName());
            // The public response must remain indistinguishable for registered
            // and unregistered addresses.
        }
    }

    private static String plainText(String resetLink) {
        return """
                Hello,

                We received a request to reset your PesaGuard Developer Platform password.
                Use the secure link below within one hour:

                %s

                This link can only be used once. If you did not request a password reset,
                you can safely ignore this email. Your password will not change.

                Need help? Contact support@pesaguard.co.ke

                PesaGuard Security
                """.formatted(resetLink);
    }

    private static String htmlContent(String escapedResetLink) {
        return """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="color-scheme" content="light">
                    <title>Reset your PesaGuard password</title>
                  </head>
                  <body style="margin:0;padding:0;background:#f4f7f3;color:#26342a;font-family:Arial,Helvetica,sans-serif;">
                    <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">
                      Your secure password reset link is ready. It expires in one hour.
                    </div>
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f4f7f3;">
                      <tr>
                        <td align="center" style="padding:42px 16px;">
                          <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;">
                            <tr>
                              <td style="padding:0 12px 20px;">
                                %s
                                <span style="float:right;padding-top:5px;font-size:10px;font-weight:700;letter-spacing:1.2px;color:#718076;">DEVELOPER PLATFORM</span>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:0;background:#fff;border:1px solid #e4eae3;border-radius:16px;overflow:hidden;">
                                <div style="height:5px;background:#2fa943;font-size:0;line-height:0;">&nbsp;</div>
                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0">
                                  <tr>
                                    <td style="padding:42px 42px 40px;">
                                      <p style="margin:0 0 12px;font-size:11px;font-weight:700;letter-spacing:1.4px;color:#25843a;">ACCOUNT SECURITY</p>
                                      <h1 style="margin:0 0 18px;font-size:30px;line-height:1.2;letter-spacing:-1px;color:#202a23;">Reset your password</h1>
                                      <p style="margin:0 0 14px;font-size:15px;line-height:1.7;color:#58635b;">We received a request to change the password for your PesaGuard Developer Platform account.</p>
                                      <p style="margin:0 0 28px;font-size:15px;line-height:1.7;color:#58635b;">Use the secure button below to choose a new password. This link expires in <strong style="color:#354139;">one hour</strong> and can only be used once.</p>
                                      <table role="presentation" cellspacing="0" cellpadding="0" border="0">
                                        <tr>
                                          <td align="center" bgcolor="#2fa943" style="border-radius:8px;">
                                            <a href="%s" style="display:inline-block;padding:14px 25px;border:1px solid #2fa943;border-radius:8px;color:#fff;font-size:14px;font-weight:700;text-decoration:none;">Reset Password</a>
                                          </td>
                                        </tr>
                                      </table>
                                      <p style="margin:28px 0 8px;font-size:12px;line-height:1.6;color:#6d776f;">If the button does not work, copy this link into your browser:</p>
                                      <p style="margin:0;overflow-wrap:anywhere;font-size:12px;line-height:1.6;color:#25843a;"><a href="%s" style="color:#25843a;">%s</a></p>
                                      <div style="height:1px;margin:28px 0 20px;background:#edf0ed;font-size:0;line-height:0;">&nbsp;</div>
                                      <p style="margin:0;font-size:13px;line-height:1.65;color:#58635b;">Didn’t request this change? You can safely ignore this email. Your password will remain unchanged.</p>
                                    </td>
                                  </tr>
                                </table>
                              </td>
                            </tr>
                            <tr>
                              <td align="center" style="padding:20px 18px 0;font-size:12px;line-height:1.7;color:#7a857d;">
                                Need help? <a href="mailto:%s" style="color:#25843a;text-decoration:underline;">Contact PesaGuard Support</a><br>
                                This is an automated security email from PesaGuard.
                              </td>
                            </tr>
                          </table>
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(EmailBranding.logoMarkup(), escapedResetLink, escapedResetLink,
                escapedResetLink, SUPPORT_ADDRESS);
    }
}
