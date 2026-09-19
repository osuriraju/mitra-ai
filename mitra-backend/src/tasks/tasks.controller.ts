import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import * as S from './schemas';
import { TasksService } from './tasks.service';

@Controller('tasks')
export class TasksController {
  constructor(private readonly tasks: TasksService) {}
  /** Tasks + projects in one call for the client store. */
  @Get('bootstrap') async bootstrap(@CurrentUser() u: string) { const [tasks, projects] = await Promise.all([this.tasks.list(u, {}), this.tasks.listProjects(u)]); return { tasks, projects }; }
  @Get() list(@CurrentUser() u: string, @Query(zod(S.ListTasksSchema)) q: S.ListTasks) { return this.tasks.list(u, q); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateTaskSchema)) b: S.CreateTask) { return this.tasks.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateTaskSchema)) b: S.UpdateTask) { return this.tasks.update(u, id, b); }
  @Post(':id/toggle') @HttpCode(200) toggle(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.tasks.toggle(u, id); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.tasks.remove(u, id); }
}

@Controller('projects')
export class ProjectsController {
  constructor(private readonly tasks: TasksService) {}
  @Get() list(@CurrentUser() u: string) { return this.tasks.listProjects(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateProjectSchema)) b: S.CreateProject) { return this.tasks.createProject(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateProjectSchema)) b: S.UpdateProject) { return this.tasks.updateProject(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.tasks.deleteProject(u, id); }
}
