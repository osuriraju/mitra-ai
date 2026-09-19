/* Screen id (as used in the design prototype) → URL. Keeping the ids lets us port `goto` targets 1:1. */
export const ROUTES = {
  login: '/login', signup: '/signup', onboarding: '/onboarding', 'forgot-password': '/forgot-password', 'reset-password': '/reset-password',
  profile: '/profile', settings: '/settings', command: '/command', notifications: '/notifications', quickadd: '/quick-add',
  states: '/states', 'design-system': '/design-system',
  today: '/today', 'today-detail': '/today/detail', 'daily-review': '/today/review',
  'tasks-inbox': '/tasks/inbox', 'tasks-today': '/tasks', 'tasks-upcoming': '/tasks/upcoming',
  projects: '/projects', 'project-detail': '/projects/mitra-ai', 'task-detail': '/tasks/1', focus: '/focus',
  habits: '/habits', 'habit-detail': '/habits/morning-walk', 'habit-history': '/habits/morning-walk/history', 'habit-edit': '/habits/new',
  goals: '/goals', 'goal-detail': '/goals/emergency-fund', 'milestone-detail': '/goals/emergency-fund/milestones/75k', 'goal-review': '/goals/review',
  money: '/money', transactions: '/money/transactions', 'expense-entry': '/money/add', categories: '/money/categories',
  budgets: '/money/budgets', recurring: '/money/recurring', subscriptions: '/money/subscriptions',
  checkin: '/wellness', 'wellness-trends': '/wellness/trends', 'sleep-detail': '/wellness/sleep', 'wellness-settings': '/wellness/settings',
  notes: '/notes', 'notes-folders': '/notes/folders', 'note-editor': '/notes/beta-launch-checklist', journal: '/journal', 'notes-search': '/notes/search',
  ai: '/ai', 'ai-suggestions': '/ai/suggestions', 'ai-log': '/ai/log',
} as const;

export type ScreenId = keyof typeof ROUTES;
export const href = (id: ScreenId) => ROUTES[id];

export type NavKey = 'today' | 'tasks' | 'goals' | 'habits' | 'money' | 'wellness' | 'notes' | 'ai' | 'settings';
export const NAV: { key: NavKey; l: string; ic: string; goto: ScreenId }[] = [
  { key: 'today', l: 'Today', ic: 'sun', goto: 'today' },
  { key: 'tasks', l: 'Tasks', ic: 'check-square', goto: 'tasks-today' },
  { key: 'goals', l: 'Goals', ic: 'target', goto: 'goals' },
  { key: 'habits', l: 'Habits', ic: 'repeat', goto: 'habits' },
  { key: 'money', l: 'Money', ic: 'wallet', goto: 'money' },
  { key: 'wellness', l: 'Wellness', ic: 'heart', goto: 'checkin' },
  { key: 'notes', l: 'Notes', ic: 'note', goto: 'notes' },
  { key: 'ai', l: 'AI', ic: 'sparkles', goto: 'ai' },
];
