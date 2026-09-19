import { z } from 'zod';

export const password = z.string().min(8, 'Password must be at least 8 characters').max(200);
export const email = z.string().trim().toLowerCase().email('Enter a valid email');

export const SignupSchema = z.object({
  name: z.string().trim().min(1, 'Enter your name').max(80),
  email,
  password,
  username: z.string().trim().toLowerCase().regex(/^[a-z0-9_.]{3,30}$/, 'Username: 3–30 letters, numbers, _ or .').optional(),
});
export const LoginSchema = z.object({
  /** Email or username */
  identifier: z.string().trim().min(1, 'Enter your email or username'),
  password: z.string().min(1, 'Enter your password'),
  remember: z.boolean().default(true),
});
export const ForgotSchema = z.object({ email });
export const ResetSchema = z.object({ token: z.string().min(20), password });

export type SignupInput = z.infer<typeof SignupSchema>;
export type LoginInput = z.infer<typeof LoginSchema>;
