import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PinoLogger } from 'nestjs-pino';
import nodemailer, { type Transporter } from 'nodemailer';
import type { Env } from '../config/env';

/** Nodemailer over SMTP. Without SMTP_HOST (dev) the mail is written to the log instead of sent. */
@Injectable()
export class MailService {
  private transporter: Transporter | null = null;
  constructor(private readonly config: ConfigService<Env, true>, private readonly logger: PinoLogger) {
    const host = config.get('SMTP_HOST', { infer: true });
    if (host) this.transporter = nodemailer.createTransport({ host, port: config.get('SMTP_PORT', { infer: true }), secure: config.get('SMTP_PORT', { infer: true }) === 465, auth: config.get('SMTP_USER', { infer: true }) ? { user: config.get('SMTP_USER', { infer: true }), pass: config.get('SMTP_PASS', { infer: true }) } : undefined });
  }

  async send(to: string, subject: string, text: string, html?: string) {
    if (!this.transporter) { this.logger.warn({ to, subject, text }, 'SMTP not configured — mail logged instead of sent'); return; }
    await this.transporter.sendMail({ from: this.config.get('MAIL_FROM', { infer: true }), to, subject, text, html });
  }

  async sendPasswordReset(to: string, name: string, link: string) {
    const text = `Hi ${name},\n\nSomeone asked to reset the password for your Mitra AI account. Open this link within the next hour to choose a new one:\n\n${link}\n\nIf this wasn't you, ignore this email — your password stays the same.`;
    await this.send(to, 'Reset your Mitra AI password', text, `<p>Hi ${name},</p><p>Someone asked to reset the password for your Mitra AI account. Open this link within the next hour to choose a new one:</p><p><a href="${link}">${link}</a></p><p>If this wasn't you, ignore this email — your password stays the same.</p>`);
  }
}
