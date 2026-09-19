import { Module } from '@nestjs/common';
import { AiController } from './ai.controller';
import { AiService } from './ai.service';
import { AiContextService } from './context.service';
import { OpenAiProvider } from './provider';

@Module({ controllers: [AiController], providers: [AiService, AiContextService, OpenAiProvider], exports: [AiService] })
export class AiModule {}
