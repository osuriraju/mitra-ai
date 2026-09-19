import { Module } from '@nestjs/common';
import { AccountsService } from './accounts.service';
import { CategoriesService } from './categories.service';
import { MoneyDefaultsService } from './money-defaults.service';
import { AccountsController, CategoriesController, MoneyController, RecurringController, SubscriptionsController, TransactionsController } from './money.controller';
import { RecurringService } from './recurring.service';
import { SubscriptionsService } from './subscriptions.service';
import { TransactionsService } from './transactions.service';

@Module({
  controllers: [MoneyController, AccountsController, CategoriesController, TransactionsController, RecurringController, SubscriptionsController],
  providers: [AccountsService, CategoriesService, TransactionsService, RecurringService, SubscriptionsService, MoneyDefaultsService],
  exports: [MoneyDefaultsService],
})
export class MoneyModule {}
