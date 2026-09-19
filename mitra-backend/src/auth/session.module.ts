import { Global, Module } from '@nestjs/common';
import { SessionService } from './session.service';

/** Global so Users (change password / delete → revoke sessions) can use it without a circular import. */
@Global()
@Module({ providers: [SessionService], exports: [SessionService] })
export class SessionModule {}
