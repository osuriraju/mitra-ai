import { Module } from '@nestjs/common';
import { ProjectsController, TasksController } from './tasks.controller';
import { TasksService } from './tasks.service';

@Module({ controllers: [TasksController, ProjectsController], providers: [TasksService], exports: [TasksService] })
export class TasksModule {}
