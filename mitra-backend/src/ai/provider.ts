import { Injectable, ServiceUnavailableException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PinoLogger } from 'nestjs-pino';
import type { Env } from '../config/env';

export type ChatMessage = { role: 'system' | 'user' | 'assistant'; content: string };
export type Completion = { text: string; usage: { input: number; output: number }; model: string; ms: number };

/** The one seam to the LLM vendor. Swap the implementation, keep the rest of the module. */
export interface AiProvider {
  enabled(): boolean;
  /** Returns the assistant's reply, which is asked to be a single JSON object. */
  completeJson(messages: ChatMessage[], opts?: { maxTokens?: number; userId?: string; reasoning?: 'low' | 'medium' | 'high' }): Promise<Completion>;
}

@Injectable()
export class OpenAiProvider implements AiProvider {
  private readonly key: string; private readonly model: string; private readonly base: string;
  constructor(config: ConfigService<Env, true>, private readonly logger: PinoLogger) {
    this.key = config.get('OPENAI_API_KEY', { infer: true }); this.model = config.get('OPENAI_MODEL', { infer: true }); this.base = config.get('OPENAI_BASE_URL', { infer: true });
    logger.setContext('ai');
  }
  enabled() { return !!this.key; }

  async completeJson(messages: ChatMessage[], opts: { maxTokens?: number; userId?: string; reasoning?: 'low' | 'medium' | 'high' } = {}): Promise<Completion> {
    if (!this.key) throw new ServiceUnavailableException({ code: 'AI_DISABLED', message: 'AI is switched off on this server (no OPENAI_API_KEY)' });
    const t0 = Date.now();
    let res: Response;
    try {
      res = await fetch(`${this.base}/chat/completions`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.key}` },
        body: JSON.stringify({ model: this.model, messages, response_format: { type: 'json_object' }, max_completion_tokens: opts.maxTokens ?? 4000, ...(opts.reasoning ? { reasoning_effort: opts.reasoning } : {}) }),
        signal: AbortSignal.timeout(60_000),
      });
    } catch (e) {
      this.logger.error({ err: e, model: this.model }, 'AI request could not be sent');
      throw new ServiceUnavailableException({ code: 'AI_ERROR', message: 'Could not reach the assistant — check the connection and try again' });
    }
    const ms = Date.now() - t0;
    if (!res.ok) {
      const body = await res.text().catch(() => '');
      this.logger.error({ status: res.status, body: body.slice(0, 500), model: this.model, ms }, 'AI request failed');
      const quota = /insufficient_quota|credit_balance_exhausted/.test(body);
      throw new ServiceUnavailableException({ code: quota ? 'AI_NO_CREDITS' : 'AI_ERROR', message: quota ? 'The OpenAI account has no API credits left — add billing to use the assistant' : res.status === 429 ? 'The assistant is busy — try again in a moment' : res.status === 401 ? 'The OpenAI API key was rejected' : 'The assistant is unavailable right now' });
    }
    const data = (await res.json()) as { choices?: { message?: { content?: string } }[]; usage?: { prompt_tokens?: number; completion_tokens?: number }; model?: string };
    const text = data.choices?.[0]?.message?.content ?? '';
    if (!text) this.logger.warn({ finish: (data as { choices?: { finish_reason?: string }[] }).choices?.[0]?.finish_reason, usage: data.usage }, 'AI returned empty content');
    const usage = { input: data.usage?.prompt_tokens ?? 0, output: data.usage?.completion_tokens ?? 0 };
    // Every AI call is logged with its token usage (spec: logs/api.log carries AI calls)
    this.logger.info({ model: data.model ?? this.model, usage, ms, userId: opts.userId }, 'AI call');
    return { text, usage, model: data.model ?? this.model, ms };
  }
}
