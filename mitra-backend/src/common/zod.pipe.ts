import { BadRequestException, Injectable, PipeTransform } from '@nestjs/common';
import type { ZodType } from 'zod';

/** `@Body(zod(schema))` — validates and returns the typed value; 400 with field issues otherwise. */
@Injectable()
export class ZodPipe<T> implements PipeTransform<unknown, T> {
  constructor(private readonly schema: ZodType<T>) {}
  transform(value: unknown): T {
    const r = this.schema.safeParse(value);
    if (r.success) return r.data;
    throw new BadRequestException({
      code: 'VALIDATION',
      message: r.error.issues[0]?.message || 'Invalid input',
      issues: r.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
    });
  }
}
export const zod = <T>(schema: ZodType<T>) => new ZodPipe(schema);
