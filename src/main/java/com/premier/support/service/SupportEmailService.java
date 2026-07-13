package com.premier.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premier.support.model.SupportTicket;
import com.premier.support.model.SupportTicketStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class SupportEmailService {

    private static final URI BREVO_SEND_EMAIL_URI = URI.create("https://api.brevo.com/v3/smtp/email");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${brevo.api-key:}")
    private String apiKey;

    @Value("${brevo.email.from-address:}")
    private String fromAddress;

    @Value("${brevo.email.from-name:Premier Transport Support}")
    private String fromName;

    @Value("${brevo.email.timeout-seconds:15}")
    private long timeoutSeconds;

    public SupportEmailService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean sendTicketDecision(SupportTicket ticket, String subject, String message) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Support email skipped for ticket {} because BREVO_API_KEY is not configured.",
                    ticket.getTicketNumber());
            return false;
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("Support email skipped for ticket {} because BREVO_FROM_ADDRESS is not configured.",
                    ticket.getTicketNumber());
            return false;
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "sender", Map.of("name", safe(fromName), "email", fromAddress.trim()),
                    "to", List.of(Map.of("email", ticket.getEmail().trim())),
                    "subject", buildSubject(ticket, subject),
                    "textContent", buildPlainText(ticket, message),
                    "htmlContent", buildHtml(ticket, message)
            ));
            HttpRequest request = HttpRequest.newBuilder(BREVO_SEND_EMAIL_URI)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("accept", "application/json")
                    .header("api-key", apiKey.trim())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Brevo rejected support email for ticket {} with HTTP {}: {}",
                        ticket.getTicketNumber(), response.statusCode(), response.body());
                return false;
            }
            log.info("Support decision email sent for ticket {} to {}.",
                    ticket.getTicketNumber(), ticket.getEmail());
            return true;
        } catch (JsonProcessingException ex) {
            log.error("Unable to create Brevo email payload for ticket {}", ticket.getTicketNumber(), ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Brevo email delivery interrupted for ticket {}", ticket.getTicketNumber(), ex);
            return false;
        } catch (IOException ex) {
            log.warn("Unable to send Brevo support email for ticket {}", ticket.getTicketNumber(), ex);
            return false;
        }
    }

    private String buildSubject(SupportTicket ticket, String fallbackSubject) {
        String status = statusLabel(ticket.getStatus());
        if (ticket.getTicketNumber() == null || ticket.getTicketNumber().isBlank()) {
            return fallbackSubject;
        }
        return "Premier Transport | Ticket " + ticket.getTicketNumber() + " is " + status;
    }

    private String buildPlainText(SupportTicket ticket, String message) {
        return """
                PREMIER TRANSPORT CORPORATION
                Passenger Support Update

                Hello,

                %s

                CASE DETAILS
                Ticket reference: %s
                Current status: %s
                Request type: %s
                Card: %s

                NEXT STEPS
                %s

                Need help?
                Contact Premier Transport support at (02) 8888-171 and include your ticket reference.

                Thank you for choosing Premier Transport.
                Premier Transport Corporation
                This is an automated notification. Please do not reply directly to this email.
                """.formatted(
                safe(message),
                safe(ticket.getTicketNumber()),
                statusLabel(ticket.getStatus()),
                issueLabel(ticket.getIssueType() != null ? ticket.getIssueType().name() : ""),
                mask(ticket.getCardNumber()),
                nextStep(ticket.getStatus()));
    }

    private String buildHtml(SupportTicket ticket, String message) {
        String statusColor = ticket.getStatus() == SupportTicketStatus.REJECTED ? "#B4232D" : "#15803D";
        String statusBg = ticket.getStatus() == SupportTicketStatus.REJECTED ? "#FDECEC" : "#EAF7EE";

        return """
                <!doctype html>
                <html lang="en">
                  <body style="margin:0;padding:0;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#1f2937;">
                    <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="background:#f3f4f6;">
                      <tr>
                        <td align="center" style="padding:32px 16px;">
                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="max-width:600px;background:#ffffff;border:1px solid #e5e7eb;">
                            <tr>
                              <td style="height:5px;background:#c9a227;font-size:0;line-height:0;">&nbsp;</td>
                            </tr>
                            <tr>
                              <td style="background:#7a1f2e;padding:26px 32px;color:#ffffff;">
                                <div style="font-size:12px;font-weight:bold;letter-spacing:1.4px;text-transform:uppercase;color:#f8d56a;">Premier Transport Corporation</div>
                                <div style="margin-top:8px;font-size:26px;font-weight:bold;line-height:1.25;">Passenger Support Update</div>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:32px;">
                                <p style="margin:0 0 14px;font-size:16px;line-height:1.6;">Hello,</p>
                                <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#374151;">%s</p>

                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="border:1px solid #e5e7eb;background:#fafafa;">
                                  <tr>
                                    <td colspan="2" style="padding:14px 18px;background:#f8fafc;border-bottom:1px solid #e5e7eb;color:#334155;font-size:12px;font-weight:bold;letter-spacing:.8px;text-transform:uppercase;">Ticket details</td>
                                  </tr>
                                  <tr>
                                    <td style="padding:13px 18px 8px;color:#64748b;font-size:13px;font-weight:bold;">Ticket reference</td>
                                    <td align="right" style="padding:13px 18px 8px;color:#111827;font-family:Consolas,Monaco,monospace;font-size:13px;font-weight:bold;">%s</td>
                                  </tr>
                                  <tr>
                                    <td style="padding:8px 18px;color:#64748b;font-size:13px;font-weight:bold;">Current status</td>
                                    <td align="right" style="padding:8px 18px;"><span style="display:inline-block;background:%s;color:%s;padding:6px 10px;font-size:11px;font-weight:bold;letter-spacing:.4px;text-transform:uppercase;">%s</span></td>
                                  </tr>
                                  <tr>
                                    <td style="padding:8px 18px;color:#64748b;font-size:13px;font-weight:bold;">Request type</td>
                                    <td align="right" style="padding:8px 18px;color:#111827;font-size:13px;font-weight:bold;">%s</td>
                                  </tr>
                                  <tr>
                                    <td style="padding:8px 18px 14px;color:#64748b;font-size:13px;font-weight:bold;">Card</td>
                                    <td align="right" style="padding:8px 18px 14px;color:#111827;font-family:Consolas,Monaco,monospace;font-size:13px;font-weight:bold;">%s</td>
                                  </tr>
                                </table>

                                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" border="0" style="margin-top:22px;background:#fff8e8;border-left:4px solid #c9a227;">
                                  <tr>
                                    <td style="padding:18px 20px;">
                                      <div style="margin-bottom:6px;color:#7a1f2e;font-size:13px;font-weight:bold;text-transform:uppercase;letter-spacing:.5px;">What happens next</div>
                                      <div style="color:#374151;font-size:14px;line-height:1.65;">%s</div>
                                    </td>
                                  </tr>
                                </table>

                                <p style="margin:24px 0 0;color:#475569;font-size:13px;line-height:1.65;">Need assistance? Contact Premier Transport support at <strong style="color:#1f2937;">(02) 8888-171</strong> and include your ticket reference.</p>
                              </td>
                            </tr>
                            <tr>
                              <td style="padding:20px 32px;background:#f8fafc;border-top:1px solid #e5e7eb;color:#64748b;font-size:12px;line-height:1.6;">
                                <strong style="color:#374151;">Premier Transport Corporation</strong><br>
                                This is an automated notification. Please do not reply directly to this email.
                              </td>
                            </tr>
                          </table>
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
