package in.techseva.cb.notifier.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final List<String> teamEmails;
    private final String fromEmail;

    public EmailNotificationService(JavaMailSender mailSender,
                                     TemplateEngine templateEngine,
                                     @Value("${notifier.team-emails:}") List<String> teamEmails,
                                     @Value("${notifier.from-email:cb-bot@techseva.in}") String fromEmail) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.teamEmails = teamEmails;
        this.fromEmail = fromEmail;
    }

    public void sendPrRaisedEmail(Vulnerability vuln, Fix fix) {
        if (teamEmails.isEmpty()) {
            log.warn("No team emails configured, skipping notification");
            return;
        }
        try {
            Context ctx = new Context();
            ctx.setVariable("vuln", vuln);
            ctx.setVariable("fix", fix);
            ctx.setVariable("prUrl", fix.prUrl());
            ctx.setVariable("severity", vuln.severity());
            ctx.setVariable("cweId", vuln.cweId());
            ctx.setVariable("confidence", String.format("%.0f%%", fix.confidence() * 100));
            ctx.setVariable("strategy", fix.strategy());
            ctx.setVariable("explanation", fix.explanation());

            String html = templateEngine.process("pr-notification", ctx);
            sendHtmlEmail("[CB] PR Raised: " + vuln.cweId() + " in " + vuln.projectKey(), html);
        } catch (Exception e) {
            log.error("Failed to send PR notification email: {}", e.getMessage(), e);
        }
    }

    public void sendEscalationEmail(Vulnerability vuln, Fix fix, String reason) {
        if (teamEmails.isEmpty()) return;
        try {
            Context ctx = new Context();
            ctx.setVariable("vuln", vuln);
            ctx.setVariable("fix", fix);
            ctx.setVariable("reason", reason);

            String html = templateEngine.process("escalation-notification", ctx);
            sendHtmlEmail("[CB][ESCALATION] Manual review required: " + vuln.cweId(), html);
        } catch (Exception e) {
            log.error("Failed to send escalation email: {}", e.getMessage(), e);
        }
    }

    private void sendHtmlEmail(String subject, String htmlBody) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromEmail);
        helper.setTo(teamEmails.toArray(new String[0]));
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        mailSender.send(message);
        log.info("Sent email '{}' to {} recipients", subject, teamEmails.size());
    }
}
