import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Patch, Post, Query } from '@nestjs/common';
import { CurrentUser } from '../common/decorators';
import { zod } from '../common/zod.pipe';
import { AccountsService } from './accounts.service';
import { CategoriesService } from './categories.service';
import { RecurringService } from './recurring.service';
import * as S from './schemas';
import { SubscriptionsService } from './subscriptions.service';
import { TransactionsService } from './transactions.service';

/* One controller per resource keeps routes discoverable: /money/accounts, /money/categories, /money/transactions, /money/recurring, /money/subscriptions. */

@Controller('money')
export class MoneyController {
  constructor(private readonly accounts: AccountsService, private readonly categories: CategoriesService, private readonly transactions: TransactionsService, private readonly recurring: RecurringService, private readonly subscriptions: SubscriptionsService) {}

  /** Everything the Money screens need in one round-trip (recent 12 months of transactions). */
  @Get('bootstrap')
  async bootstrap(@CurrentUser() userId: string) {
    const from = new Date(); from.setUTCMonth(from.getUTCMonth() - 12);
    const [accounts, categories, transactions, recurring, subscriptions] = await Promise.all([
      this.accounts.list(userId), this.categories.list(userId), this.transactions.list(userId, { from: from.toISOString().slice(0, 10), limit: 5000 }), this.recurring.list(userId), this.subscriptions.list(userId),
    ]);
    return { accounts, categories, transactions, recurring, subscriptions };
  }
}

@Controller('money/accounts')
export class AccountsController {
  constructor(private readonly accounts: AccountsService) {}
  @Get() list(@CurrentUser() u: string) { return this.accounts.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateAccountSchema)) b: S.CreateAccount) { return this.accounts.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateAccountSchema)) b: S.UpdateAccount) { return this.accounts.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.accounts.remove(u, id); }
}

@Controller('money/categories')
export class CategoriesController {
  constructor(private readonly categories: CategoriesService) {}
  @Get() list(@CurrentUser() u: string) { return this.categories.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateCategorySchema)) b: S.CreateCategory) { return this.categories.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateCategorySchema)) b: S.UpdateCategory) { return this.categories.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.categories.remove(u, id); }
}

@Controller('money/transactions')
export class TransactionsController {
  constructor(private readonly transactions: TransactionsService) {}
  @Get() list(@CurrentUser() u: string, @Query(zod(S.ListTransactionsSchema)) q: S.ListTransactions) { return this.transactions.list(u, q); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateTransactionSchema)) b: S.CreateTransaction) { return this.transactions.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateTransactionSchema)) b: S.UpdateTransaction) { return this.transactions.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.transactions.remove(u, id); }
}

@Controller('money/recurring')
export class RecurringController {
  constructor(private readonly recurring: RecurringService) {}
  @Get() list(@CurrentUser() u: string) { return this.recurring.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateRecurringSchema)) b: S.CreateRecurring) { return this.recurring.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateRecurringSchema)) b: S.UpdateRecurring) { return this.recurring.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.recurring.remove(u, id); }
  @Post(':id/pay') @HttpCode(200) pay(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.PayRecurringSchema)) b: S.PayRecurring) { return this.recurring.pay(u, id, b); }
}

@Controller('money/subscriptions')
export class SubscriptionsController {
  constructor(private readonly subscriptions: SubscriptionsService) {}
  @Get() list(@CurrentUser() u: string) { return this.subscriptions.list(u); }
  @Post() create(@CurrentUser() u: string, @Body(zod(S.CreateSubscriptionSchema)) b: S.CreateSubscription) { return this.subscriptions.create(u, b); }
  @Patch(':id') update(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string, @Body(zod(S.UpdateSubscriptionSchema)) b: S.UpdateSubscription) { return this.subscriptions.update(u, id, b); }
  @Delete(':id') remove(@CurrentUser() u: string, @Param('id', ParseUUIDPipe) id: string) { return this.subscriptions.remove(u, id); }
}
