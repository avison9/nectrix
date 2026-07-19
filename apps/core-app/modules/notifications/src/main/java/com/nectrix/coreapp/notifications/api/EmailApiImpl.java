package com.nectrix.coreapp.notifications.api;

import com.nectrix.coreapp.notifications.service.EmailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailApiImpl implements EmailApi {

  private final EmailSender emailSender;

  public EmailApiImpl(EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  @Override
  public boolean sendRaw(String recipientEmail, String subject, String body) {
    return emailSender.send(recipientEmail, subject, body);
  }
}
