import { z } from 'zod';
import { email, password } from '../auth/schemas';

export const UpdateProfileSchema = z.object({
  name: z.string().trim().min(1, 'Enter your name').max(80).optional(),
  email: email.optional(),
  phone: z.string().trim().max(30).nullable().optional(),
  username: z.string().trim().toLowerCase().regex(/^[a-z0-9_.]{3,30}$/, 'Username: 3–30 letters, numbers, _ or .').nullable().optional(),
});
/** Settings are a free-form client document; we only cap the size and require an object. */
export const UpdateSettingsSchema = z.record(z.string(), z.unknown()).refine((o) => JSON.stringify(o).length < 20_000, 'Settings too large');
export const ChangePasswordSchema = z.object({ currentPassword: z.string().min(1, 'Enter your current password'), newPassword: password });
export const DeleteAccountSchema = z.object({ password: z.string().min(1, 'Enter your password to confirm') });
export const OnboardedSchema = z.object({ settings: UpdateSettingsSchema.optional() });

export type UpdateProfileInput = z.infer<typeof UpdateProfileSchema>;
