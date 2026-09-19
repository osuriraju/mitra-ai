-- CreateTable
CREATE TABLE "Note" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL DEFAULT '',
    "folder" TEXT NOT NULL DEFAULT 'Personal',
    "tags" TEXT[] DEFAULT ARRAY[]::TEXT[],
    "journalDate" DATE,
    "ai" BOOLEAN NOT NULL DEFAULT false,
    "mood" INTEGER,
    "version" INTEGER NOT NULL DEFAULT 1,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Note_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WellnessEntry" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "mood" INTEGER,
    "sleepStart" TEXT,
    "sleepEnd" TEXT,
    "steps" INTEGER,
    "water" INTEGER,
    "weight" DOUBLE PRECISION,
    "note" TEXT,
    "savedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "WellnessEntry_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WellnessSettings" (
    "userId" TEXT NOT NULL,
    "height" INTEGER NOT NULL DEFAULT 170,
    "sleepTarget" INTEGER NOT NULL DEFAULT 450,
    "stepsTarget" INTEGER NOT NULL DEFAULT 8000,
    "waterTarget" INTEGER NOT NULL DEFAULT 8,
    "track" JSONB NOT NULL DEFAULT '{"mood":true,"sleep":true,"steps":true,"water":true,"bmi":true}',
    "shareAI" BOOLEAN NOT NULL DEFAULT false,
    "showOnToday" BOOLEAN NOT NULL DEFAULT true,
    "morningReminder" TEXT NOT NULL DEFAULT '07:30',
    "eveningReminder" TEXT NOT NULL DEFAULT '21:00',

    CONSTRAINT "WellnessSettings_pkey" PRIMARY KEY ("userId")
);

-- CreateIndex
CREATE INDEX "Note_userId_updatedAt_idx" ON "Note"("userId", "updatedAt" DESC);

-- CreateIndex
CREATE INDEX "Note_userId_journalDate_idx" ON "Note"("userId", "journalDate");

-- CreateIndex
CREATE UNIQUE INDEX "WellnessEntry_userId_date_key" ON "WellnessEntry"("userId", "date");

-- AddForeignKey
ALTER TABLE "Note" ADD CONSTRAINT "Note_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WellnessEntry" ADD CONSTRAINT "WellnessEntry_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WellnessSettings" ADD CONSTRAINT "WellnessSettings_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
