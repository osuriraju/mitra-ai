-- CreateTable
CREATE TABLE "AiAction" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "action" TEXT NOT NULL,
    "detail" TEXT NOT NULL DEFAULT '',
    "state" TEXT NOT NULL DEFAULT 'Approved by you',
    "tone" TEXT NOT NULL DEFAULT 'success',
    "revertible" BOOLEAN NOT NULL DEFAULT false,
    "revert" JSONB,
    "revertedAt" TIMESTAMP(3),
    "revertOfId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AiAction_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "AiAction_userId_createdAt_idx" ON "AiAction"("userId", "createdAt" DESC);

-- AddForeignKey
ALTER TABLE "AiAction" ADD CONSTRAINT "AiAction_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
