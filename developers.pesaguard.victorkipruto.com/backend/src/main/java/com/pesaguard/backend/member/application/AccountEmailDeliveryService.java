package com.pesaguard.backend.member.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.net.URLEncoder;

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
import com.pesaguard.backend.common.exception.BusinessException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/** Sends account security notices using the PesaGuard password-reset email design. */
@Service
public class AccountEmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailDeliveryService.class);
    private static final String SUPPORT_ADDRESS = "support@pesaguard.co.ke";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String portalUrl;

    public AccountEmailDeliveryService(
            JavaMailSender mailSender,
            @Value("${pesaguard.identity.from-email}") String fromAddress,
            @Value("${pesaguard.identity.verification-url}") String portalUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.portalUrl = portalUrl.replaceAll("/+$", "");
    }

    public void sendPasswordChangeCode(String email, String code) {
        sendRequired(email, "Verify your PesaGuard password change",
                "Verify your password change",
                "Use this six-digit code to confirm the password change you requested.",
                code, "This code expires in 10 minutes. If you did not request this change, ignore this email.");
    }

    public void sendEmailVerificationCode(String email, String code) {
        sendRequired(email, "Your PesaGuard verification code",
                "Verify your email address",
                "Enter this six-digit code in the PesaGuard Developer Platform to verify your email address.",
                code, "This code expires in 10 minutes. If you did not create an account, ignore this email.");
    }

    public void sendLoginEmailCode(String email, String code) {
        sendRequired(email, "Your PesaGuard sign-in verification code",
                "Verify your sign-in",
                "Enter this six-digit code to complete your PesaGuard Developer Platform sign-in.",
                code, "This code expires in 10 minutes. If you did not try to sign in, change your password.");
    }

    public void sendPasswordChanged(String email) {
        sendBestEffort(email, "Your PesaGuard password was changed",
                "Your password was changed",
                "The password for your PesaGuard Developer Platform account was changed successfully.",
                null, "If you did not make this change, contact support immediately.");
    }

    public void sendAccountPaused(String email, Instant until) {
        sendBestEffort(email, "Your PesaGuard account is paused",
                "Account pause started",
                "Sign-in has been deactivated and active credentials were revoked.",
                until.toString(), "Your account will reactivate automatically at the date and time shown above.");
    }

    public void sendAccountReactivated(String email) {
        sendBestEffort(email, "Your PesaGuard account is active again",
                "Account reactivated",
                "The pause period has ended and your PesaGuard account is active again.",
                null, "Sign in to continue using the Developer Platform.");
    }

    public void sendDeletionRequested(String email, Instant completesAt) {
        sendBestEffort(email, "Your PesaGuard account deletion was scheduled",
                "Account deletion scheduled",
                "We received a request to delete your PesaGuard Developer Platform account.",
                completesAt.toString(), "Contact support before this date if you did not request deletion or want to cancel.");
    }

    public void sendAccountDeleted(String email) {
        sendBestEffort(email, "Your PesaGuard account was deleted",
                "Account deletion completed",
                "Your account deletion request has been completed.",
                null, "This notice confirms the account lifecycle request has finished.");
    }

    public void sendInvitation(
            String email, String token, String organizationName, String inviterName, String role, Instant expiresAt) {
        String acceptLink = portalUrl + "/accept-invitation?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String expiration = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(expiresAt) + " (UTC)";
        String subject = "You have been invited to join " + organizationName;
        String text = inviterName + " invited you to join " + organizationName + " as a " + role
                + ".\n\nAccept the invitation: " + acceptLink + "\n\nThis invitation expires on "
                + expiration + ". If you were not expecting this invitation, ignore this email.";
        String escapedOrg = HtmlUtils.htmlEscape(organizationName);
        String escapedInviter = HtmlUtils.htmlEscape(inviterName);
        String escapedRole = HtmlUtils.htmlEscape(role);
        String escapedLink = HtmlUtils.htmlEscape(acceptLink);
        String html = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"></head>
                <body style="margin:0;padding:0;background:#f4f7f3;color:#26342a;font-family:Arial,Helvetica,sans-serif;">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f7f3;"><tr><td align="center" style="padding:42px 16px;">
                    <table role="presentation" width="600" cellspacing="0" cellpadding="0" style="width:100%%;max-width:600px;">
                      <tr><td style="padding:0 12px 20px;">%s</td></tr>
                      <tr><td style="background:#fff;border:1px solid #e4eae3;border-radius:16px;overflow:hidden;"><div style="height:5px;background:#2fa943;"></div><div style="padding:38px 42px;">
                        <p style="margin:0 0 12px;font-size:11px;font-weight:700;letter-spacing:1.4px;color:#25843a;">DEVELOPER PLATFORM</p>
                        <h1 style="margin:0 0 18px;font-size:28px;color:#202a23;">You are invited</h1>
                        <p style="font-size:15px;line-height:1.7;color:#58635b;">%s invited you to join <strong>%s</strong> as a %s.</p>
                        <p style="margin:26px 0;"><a href="%s" style="display:inline-block;padding:14px 25px;border-radius:8px;background:#2fa943;color:#fff;font-size:14px;font-weight:700;text-decoration:none;">Accept invitation</a></p>
                        <p style="font-size:12px;line-height:1.6;color:#6d776f;">This invitation expires on %s. If you were not expecting it, you can ignore this email.</p>
                      </div></td></tr>
                      <tr><td align="center" style="padding:20px;font-size:12px;color:#7a857d;">Need help? <a href="mailto:%s" style="color:#25843a;">Contact PesaGuard Support</a></td></tr>
                    </table>
                  </td></tr></table>
                </body></html>
                """.formatted(EmailBranding.logoMarkup(), escapedInviter, escapedOrg, escapedRole, escapedLink,
                HtmlUtils.htmlEscape(expiration), SUPPORT_ADDRESS);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setReplyTo(SUPPORT_ADDRESS);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
        } catch (MessagingException | MailException exception) {
            log.error("Invitation email delivery failed ({})", exception.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "We could not deliver the invitation email. Please try again later.");
        }
    }

    public boolean sendNotification(String email, String subject, String body) {
        return sendNotification(email, subject, body, null);
    }

    public boolean sendNotification(String email, String subject, String body, String idempotencyKey) {
        try {
            send(email, subject, subject, body, null,
                    "This is an automated notification from the PesaGuard Developer Platform.",
                    idempotencyKey);
            return true;
        } catch (MessagingException | MailException exception) {
            log.warn("Notification email delivery failed ({})", exception.getClass().getSimpleName());
            return false;
        }
    }

    private void sendRequired(String email, String subject, String heading, String body, String code, String note) {
        try {
            send(email, subject, heading, body, code, note, null);
        } catch (MessagingException | MailException exception) {
            log.error("Account security email delivery failed ({})", exception.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "We could not deliver the verification email. Please try again later.");
        }
    }

    private void sendBestEffort(String email, String subject, String heading, String body, String detail, String note) {
        try {
            send(email, subject, heading, body, detail, note, null);
        } catch (MessagingException | MailException exception) {
            log.error("Account notice delivery failed ({})", exception.getClass().getSimpleName());
        }
    }

    private void send(String email, String subject, String heading, String body, String detail, String note,
            String idempotencyKey)
            throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(fromAddress);
        helper.setTo(email);
        helper.setReplyTo(SUPPORT_ADDRESS);
        helper.setSubject(subject);
        helper.setText(plainText(heading, body, detail, note), htmlContent(heading, body, detail, note));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            message.setHeader("X-Resend-Idempotency-Key", idempotencyKey);
        }
        mailSender.send(message);
    }

    private static String plainText(String heading, String body, String detail, String note) {
        return """
                PesaGuard Developer Platform

                %s

                %s
                %s

                %s

                Need help? Contact %s
                PesaGuard Security
                """.formatted(heading, body, detail == null ? "" : detail, note, SUPPORT_ADDRESS);
    }

    private static String htmlContent(String heading, String body, String detail, String note) {
        String escapedHeading = HtmlUtils.htmlEscape(heading);
        String escapedBody = HtmlUtils.htmlEscape(body);
        String escapedDetail = detail == null ? "" : HtmlUtils.htmlEscape(detail);
        String escapedNote = HtmlUtils.htmlEscape(note);
        String detailMarkup = detail == null ? "" : """
                <div style="margin:20px 0;padding:12px 14px;border:1px solid #e4eae3;border-radius:8px;background:#f4f7f3;color:#26342a;font-family:monospace;font-size:15px;font-weight:700;">%s</div>
                """.formatted(escapedDetail);
        return """
                <!doctype html>
                <html lang="en">
                  <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="color-scheme" content="light"><title>%s</title></head>
                  <body style="margin:0;padding:0;background:#f4f7f3;color:#26342a;font-family:Arial,Helvetica,sans-serif;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f4f7f3;">
                      <tr><td align="center" style="padding:42px 16px;">
                        <table role="presentation" width="600" cellspacing="0" cellpadding="0" border="0" style="width:100%%;max-width:600px;">
                          <tr><td style="padding:0 12px 20px;">%s<span style="float:right;padding-top:5px;font-size:10px;font-weight:700;letter-spacing:1.2px;color:#718076;">DEVELOPER PLATFORM</span></td></tr>
                          <tr><td style="padding:0;background:#fff;border:1px solid #e4eae3;border-radius:16px;overflow:hidden;">
                            <div style="height:5px;background:#2fa943;font-size:0;line-height:0;">&nbsp;</div>
                            <div style="padding:38px 42px 36px;">
                              <p style="margin:0 0 12px;font-size:11px;font-weight:700;letter-spacing:1.4px;color:#25843a;">ACCOUNT SECURITY</p>
                              <h1 style="margin:0 0 18px;font-size:28px;line-height:1.2;letter-spacing:-1px;color:#202a23;">%s</h1>
                              <p style="margin:0 0 14px;font-size:15px;line-height:1.7;color:#58635b;">%s</p>
                              %s
                              <div style="height:1px;margin:25px 0 18px;background:#edf0ed;font-size:0;line-height:0;">&nbsp;</div>
                              <p style="margin:0;font-size:13px;line-height:1.65;color:#58635b;">%s</p>
                            </div>
                          </td></tr>
                          <tr><td align="center" style="padding:20px 18px 0;font-size:12px;line-height:1.7;color:#7a857d;">Need help? <a href="mailto:%s" style="color:#25843a;">Contact PesaGuard Support</a><br>This is an automated security email from PesaGuard.</td></tr>
                        </table>
                      </td></tr>
                    </table>
                  </body>
                </html>
                """.formatted(EmailBranding.logoMarkup(), escapedHeading, escapedHeading, escapedBody,
                detailMarkup, escapedNote, SUPPORT_ADDRESS);
    }
}
