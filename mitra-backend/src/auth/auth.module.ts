import { Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { MoneyModule } from '../money/money.module';
import { UsersModule } from '../users/users.module';
import { AuthController } from './auth.controller';
import { AuthGuard } from './auth.guard';
import { AuthService } from './auth.service';
import { MailService } from './mail.service';
import { SessionService } from './session.service';

@Module({
  imports: [UsersModule, MoneyModule],
  controllers: [AuthController],
  providers: [AuthService, MailService, { provide: APP_GUARD, useClass: AuthGuard }],
  exports: [AuthService],
})
export class AuthModule {}
