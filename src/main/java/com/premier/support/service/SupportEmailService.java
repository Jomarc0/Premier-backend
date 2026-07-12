package com.premier.support.service;

import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketStatus;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportEmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${support.email.from:noreply@premiertransport.local}")
    private String fromAddress;

    @Value("${spring.mail.host:}")
    private String mailHost;

    public boolean sendTicketDecision(SupportTicket ticket, String subject, String message) {
        if (mailHost == null || mailHost.isBlank()) {
            log.warn("Support email skipped for ticket {} because SPRING_MAIL_HOST is not configured.",
                    ticket.getTicketNumber());
            return false;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.info("Mail sender is not configured. Ticket {} email to {} skipped: {}",
                    ticket.getTicketNumber(), ticket.getEmail(), message);
            return false;
        }

        try {
            MimeMessage mail = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mail, "UTF-8");
            helper.setFrom(fromAddress, "Premier Transport Support");
            helper.setTo(ticket.getEmail());
            helper.setSubject(buildSubject(ticket, subject));
            helper.setText(buildPlainText(ticket, message), buildHtml(ticket, message));
            mailSender.send(mail);
            log.info("Support decision email sent for ticket {} to {}.",
                    ticket.getTicketNumber(), ticket.getEmail());
            return true;
        } catch (MessagingException | UnsupportedEncodingException ex) {
            log.warn("Unable to send formatted support email for ticket {}", ticket.getTicketNumber(), ex);
            throw new RuntimeException("Unable to send support email confirmation.");
        }
    }

    private String buildSubject(SupportTicket ticket, String fallbackSubject) {
        String status = statusLabel(ticket.getStatus());
        if (ticket.getTicketNumber() == null || ticket.getTicketNumber().isBlank()) {
            return fallbackSubject;
        }
        return "Premier Transport Support - " + ticket.getTicketNumber() + " " + status;
    }

    private String buildPlainText(SupportTicket ticket, String message) {
        return """
                Premier Transport Support

                Ticket Update

                Ticket Number: %s
                Status: %s
                Request Type: %s
                Card Number: %s

                %s

                What happens next:
                %s

                For urgent concerns, contact Premier Transport support at (02) 8888-171.

                Thank you,
                Premier Transport Support Team
                """.formatted(
                safe(ticket.getTicketNumber()),
                statusLabel(ticket.getStatus()),
                issueLabel(ticket.getIssueType() != null ? ticket.getIssueType().name() : ""),
                mask(ticket.getCardNumber()),
                safe(message),
                nextStep(ticket.getStatus()));
    }

    private String buildHtml(SupportTicket ticket, String message) {
        String statusColor = ticket.getStatus() == SupportTicketStatus.REJECTED ? "#B4232D" : "#15803D";
        String statusBg = ticket.getStatus() == SupportTicketStatus.REJECTED ? "#FDECEC" : "#EAF7EE";

        return """
                <!doctype html>
                <html>
                  <body style="margin:0;background:#f4f6f8;padding:24px;font-family:Arial,Helvetica,sans-serif;color:#1f2937;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid #e5e7eb;">
                      <tr>
                        <td style="background:#7B181E;padding:22px 24px;color:#ffffff;">
                          <div style="font-size:12px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:#FACC15;">Premier Transport</div>
                          <div style="font-size:22px;font-weight:800;margin-top:6px;">Support Ticket Update</div>
                        </td>
                      </tr>
                      <tr>
                        <td style="padding:24px;">
                          <p style="margin:0 0 16px;font-size:15px;line-height:1.6;">Hello,</p>
                          <p style="margin:0 0 18px;font-size:15px;line-height:1.6;">%s</p>

                          <div style="border:1px solid #e5e7eb;border-radius:14px;padding:16px;background:#fafafa;">
                            <table role="presentation" width="100%%" cellspacing="0" cellpadding="0">
                              <tr>
                                <td style="padding:7px 0;color:#64748b;font-size:13px;font-weight:700;">Ticket Number</td>
                                <td style="padding:7px 0;text-align:right;font-size:13px;font-weight:800;color:#111827;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:7px 0;color:#64748b;font-size:13px;font-weight:700;">Status</td>
                                <td style="padding:7px 0;text-align:right;">
                                  <span style="display:inline-block;border-radius:999px;background:%s;color:%s;padding:6px 10px;font-size:11px;font-weight:800;text-transform:uppercase;">%s</span>
                                </td>
                              </tr>
                              <tr>
                                <td style="padding:7px 0;color:#64748b;font-size:13px;font-weight:700;">Request Type</td>
                                <td style="padding:7px 0;text-align:right;font-size:13px;font-weight:800;color:#111827;">%s</td>
                              </tr>
                              <tr>
                                <td style="padding:7px 0;color:#64748b;font-size:13px;font-weight:700;">Card Number</td>
                                <td style="padding:7px 0;text-align:right;font-size:13px;font-weight:800;color:#111827;">%s</td>
                              </tr>
                            </table>
                          </div>

                          <div style="margin-top:18px;padding:14px 16px;border-left:4px solid #7B181E;background:#fff7f8;border-radius:10px;">
                            <div style="font-size:13px;font-weight:800;color:#7B181E;margin-bottom:5px;">What happens next</div>
                            <div style="font-size:14px;line-height:1.6;color:#374151;">%s</div>
                          </div>

                          <p style="margin:20px 0 0;font-size:13px;line-height:1.6;color:#64748b;">
                            For urgent concerns, contact Premier Transport support at <strong>(02) 8888-171</strong>.
                          </p>
                        </td>
                      </tr>
                      <tr>
                        <td style="background:#f8fafc;padding:16px 24px;color:#64748b;font-size:12px;line-height:1.5;">
                          This is an automated support notification from Premier Transport.
                        </td>
                      </tr>
                    </table>
                  </body>
                </html>
                """.formatted(
                escapeHtml(safe(message)),
                escapeHtml(safe(ticket.getTicketNumber())),
                statusBg,
                statusColor,
                escapeHtml(statusLabel(ticket.getStatus())),
                escapeHtml(issueLabel(ticket.getIssueType() != null ? ticket.getIssueType().name() : "")),
                escapeHtml(mask(ticket.getCardNumber())),
                escapeHtml(nextStep(ticket.getStatus())));
    }

    private String nextStep(SupportTicketStatus status) {
        if (status == SupportTicketStatus.RESOLVED) {
            return "Your request has been completed. Please keep this email and ticket number for your records.";
        }
        if (status == SupportTicketStatus.REJECTED) {
            return "Your request was not approved. Review the message above or contact support if you need clarification.";
        }
        return "Our admin team is reviewing your request. You will receive another email once there is an update.";
    }

    private String statusLabel(SupportTicketStatus status) {
        if (status == null) return "Updated";
        return issueLabel(status.name());
    }

    private String issueLabel(String value) {
        if (value == null || value.isBlank()) return "-";
        String[] parts = value.toLowerCase().split("_");
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return label.toString();
    }

    private String mask(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) return "-";
        String value = cardNumber.trim();
        if (value.length() <= 4) return "****";
        return "**** " + value.substring(value.length() - 4);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String escapeHtml(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
