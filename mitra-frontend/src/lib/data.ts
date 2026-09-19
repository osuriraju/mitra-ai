/* Representative, realistic sample data (Indian rupee context) — ported from the design prototype.
   This is the dummy data the frontend runs on until the backend exists. */
export type Task = { id?: number; title: string; project?: string; due?: string; pri?: 'high' | 'med' | 'low'; est?: string; goal?: string; habit?: string; overdue?: boolean; sub?: string; amount?: string; recurring?: boolean; done?: boolean };
export type Habit = { name: string; target: string; done?: boolean; progress?: number; total?: number; streak: number; best: number; rate: number; icon: string; week: number[] };
export type GoalStatus = 'on-track' | 'attention' | 'behind' | 'slipping';
export type Goal = { name: string; type: string; progress: number; current?: string; target?: string; due: string; next: string; term: string; status: GoalStatus; links: string };
export type Tx = { m: string; cat: string; amt: number; when: string; acc: string; method: string; emoji: string; src?: string; recurring?: boolean };
export type Note = { t: string; p: string; tags: string[]; when: string; folder: string; ai?: boolean };
export type Notif = { t: string; s: string; when: string; icon: string; tone: string; unread?: boolean };

export const USER = {name:'Aarav', full:'Aarav Mehta', initials:'AM', email:'aarav@example.com'};
export const TODAY_LONG = 'Wednesday, 16 September 2026';

export const TASKS: Task[] = [
 {id:1,title:'Send Q3 invoice to Northwind Studio',project:'Freelance',due:'5:00 PM',pri:'high',est:'30m',goal:'Emergency fund'},
 {id:3,title:'Review PR #214 — auth refactor',project:'Mitra AI',due:'3:00 PM',pri:'high',est:'45m'},
 {id:2,title:'Book dentist appointment',project:'Personal',due:'Today',pri:'med',est:'10m'},
 {id:6,title:'Call Amma',project:'Family',due:'7:00 PM',pri:'low'},
 {id:7,title:'Read 20 pages — Atomic Habits',project:'Learning',due:'Today',pri:'low',est:'25m',habit:'Read'},
 {id:5,title:'Renew car insurance',project:'Personal',due:'Overdue · Mon 14 Sep',pri:'high',overdue:true},
 {id:4,title:'Prepare slides for Friday demo',project:'Mitra AI',due:'Fri 18 Sep',pri:'med',est:'2h',sub:'2/5'},
 {id:8,title:'Groceries: milk, eggs, spinach',project:'Home',due:'Tomorrow'},
 {id:10,title:'Pay electricity bill',project:'Home',due:'Sat 19 Sep',amount:'₹2,140',recurring:true},
 {id:9,title:'Write weekly review',project:'Personal',due:'Sun 20 Sep',recurring:true},
 {id:11,title:'Draft onboarding copy',project:'Mitra AI',due:'Mon 21 Sep',est:'1h'},
 {id:12,title:'Idea: habit streak recovery UX',project:'Inbox'},
 {id:13,title:'Buy birthday gift for Priya',project:'Inbox'},
 {id:14,title:'Check flight prices for Goa trip',project:'Inbox'},
 {id:15,title:'Reply to accountant about GST filing',project:'Inbox'},
];
export const byId = (id: number) => TASKS.find((t) => t.id === id);

export const HABITS: Habit[] = [
 {name:'Morning walk',target:'30 min · daily',done:true,streak:12,best:41,rate:86,icon:'activity',week:[1,1,0,1,1,1,1]},
 {name:'Drink water',target:'8 glasses',progress:5,total:8,streak:4,best:19,rate:71,icon:'droplet',week:[1,1,1,0,1,1,0]},
 {name:'Read',target:'20 min · daily',done:false,streak:0,best:23,rate:58,icon:'book',week:[1,0,1,1,0,0,0]},
 {name:'Meditate',target:'10 min · daily',done:true,streak:7,best:30,rate:64,icon:'sun',week:[0,1,1,1,1,1,1]},
 {name:'No sugar',target:'Mon–Fri',done:false,streak:2,best:14,rate:45,icon:'x',week:[1,0,0,1,1,0,0]},
 {name:'Sleep by 11 pm',target:'5× a week',done:false,streak:3,best:12,rate:52,icon:'moon',week:[1,1,0,1,0,1,0]},
];

export const GOALS: Goal[] = [
 {name:'Build ₹1,00,000 emergency fund',type:'Numeric',progress:62,current:'₹62,000',target:'₹1,00,000',due:'Mar 2027',next:'Transfer ₹8,000 on 1 Oct',term:'Medium-term',status:'on-track',links:'2 tasks · 1 budget'},
 {name:'Run a 10K',type:'Milestone',progress:40,due:'Dec 2026',next:'5K run on Saturday',term:'Short-term',status:'attention',links:'1 habit · 3 tasks'},
 {name:'Ship Mitra AI beta',type:'Milestone',progress:55,due:'Nov 2026',next:'Finish auth refactor',term:'Short-term',status:'on-track',links:'1 project · 14 tasks'},
 {name:'Read 24 books this year',type:'Numeric',progress:58,current:'14',target:'24',due:'Dec 2026',next:'Finish Atomic Habits',term:'Long-term',status:'behind',links:'1 habit'},
 {name:'Learn Spanish to B1',type:'Habit-supported',progress:22,due:'Jun 2027',next:'Complete Unit 4',term:'Long-term',status:'slipping',links:'1 habit'},
];

export const TX: Tx[] = [
 {m:'Swiggy',cat:'Food',amt:-450,when:'Today · 1:12 PM',acc:'HDFC ••4021',method:'UPI',emoji:'🍛',src:'AI capture'},
 {m:'Metro recharge',cat:'Transport',amt:-500,when:'Today · 8:40 AM',acc:'HDFC ••4021',method:'UPI',emoji:'🚇'},
 {m:'Blinkit',cat:'Groceries',amt:-1230,when:'Yesterday · 7:05 PM',acc:'ICICI Credit',method:'Card',emoji:'🛒'},
 {m:'Uber',cat:'Transport',amt:-312,when:'Mon 14 Sep',acc:'HDFC ••4021',method:'UPI',emoji:'🚕'},
 {m:'Decathlon',cat:'Shopping',amt:-2499,when:'Sun 13 Sep',acc:'ICICI Credit',method:'Card',emoji:'🛍️'},
 {m:'Netflix',cat:'Subscriptions',amt:-649,when:'Sat 12 Sep',acc:'ICICI Credit',method:'Auto-debit',emoji:'📺',recurring:true},
 {m:'Apollo Pharmacy',cat:'Health',amt:-780,when:'Fri 11 Sep',acc:'Cash',method:'Cash',emoji:'💊'},
 {m:'Rent',cat:'Housing',amt:-28000,when:'3 Sep',acc:'HDFC ••4021',method:'NEFT',emoji:'🏠',recurring:true},
 {m:'Transfer to Savings',cat:'Transfer',amt:-8000,when:'2 Sep',acc:'HDFC → SBI',method:'IMPS',emoji:'🏦'},
 {m:'Salary — Beyond Labs',cat:'Income',amt:125000,when:'1 Sep',acc:'HDFC ••4021',method:'NEFT',emoji:'💼'},
];
export const ACCOUNTS = [{n:'HDFC Savings',s:'••4021',bal:'₹1,18,400'},{n:'ICICI Credit',s:'••7710',bal:'−₹28,120'},{n:'SBI Savings',s:'••3388 · Emergency',bal:'₹3,90,000'},{n:'Cash',s:'Wallet',bal:'₹2,300'}];
export const CATS = ['🍛 Food','🚇 Transport','🛒 Groceries','🛍️ Shopping','💊 Health','🎬 Fun','🏠 Home','📚 Learning'];
export const BUDGETS = [
 {cat:'Food & dining',spent:6200,limit:8000,emoji:'🍛'},
 {cat:'Groceries',spent:5100,limit:7000,emoji:'🛒'},
 {cat:'Transport',spent:2900,limit:3000,emoji:'🚇'},
 {cat:'Shopping',spent:4300,limit:4000,emoji:'🛍️'},
 {cat:'Entertainment',spent:1800,limit:3000,emoji:'🎬'},
 {cat:'Health',spent:780,limit:2000,emoji:'💊'},
];
export const SUBS = [
 {n:'Netflix',amt:649,cyc:'Monthly',next:'12 Oct',c:'#E50914',l:'N'},
 {n:'Spotify',amt:119,cyc:'Monthly',next:'20 Sep',c:'#1DB954',l:'S'},
 {n:'iCloud+ 200GB',amt:219,cyc:'Monthly',next:'3 Oct',c:'#3B82F6',l:'i'},
 {n:'Gym — Cult.fit',amt:1500,cyc:'Monthly',next:'1 Oct',c:'#F97316',l:'C'},
 {n:'Notion Plus',amt:830,cyc:'Monthly',next:'28 Sep',c:'#111827',l:'N'},
 {n:'Amazon Prime',amt:1499,cyc:'Yearly',next:'14 Jan 2027',c:'#0EA5E9',l:'a'},
];
export const NOTES: Note[] = [
 {t:'Beta launch checklist',p:'Auth refactor · onboarding copy · analytics events · PWA install prompt · empty states for Money. Blockers: receipt upload on iOS Safari.',tags:['Mitra AI','checklist'],when:'2h ago',folder:'Work'},
 {t:'Idea: streak recovery UX',p:'Missed days should not reset to zero visually. Show "recovering" state and a soft nudge instead of a broken chain. Compare with Duolingo streak freeze.',tags:['ideas','habits'],when:'Yesterday',folder:'Ideas'},
 {t:'Book notes — Atomic Habits',p:'Make it obvious, attractive, easy, satisfying. Habit stacking: after [current habit], I will [new habit]. Environment design beats motivation.',tags:['books','learning'],when:'Sun',folder:'Learning'},
 {t:'Journal — 15 Sep',p:'Slept late again. Good focus block in the morning though. Need to move the run earlier; evenings keep getting eaten by calls.',tags:['journal'],when:'Yesterday',folder:'Journal',ai:true},
 {t:'Goa trip plan',p:'Dates: 10–14 Dec. Budget ₹25,000. Flights ~₹9k return, stay ~₹10k. Check Priya\'s leave. Ideas: Palolem, Fontainhas walk, Saturday night market.',tags:['travel','money'],when:'Mon',folder:'Personal'},
 {t:'Meeting notes — design review',p:'Keep Today under 5 sections by default. Money card only shows today spend + budget bar. Move AI briefing above the fold on desktop only.',tags:['Mitra AI','meeting'],when:'Fri',folder:'Work'},
];
export const NOTIFS: Notif[] = [
 {t:'Invoice to Northwind due at 5:00 PM',s:'Task · high priority',when:'10m',icon:'check-square',tone:'accent',unread:true},
 {t:'Shopping budget exceeded by ₹300',s:'Money · Shopping · September',when:'1h',icon:'wallet',tone:'warning',unread:true},
 {t:'You slept 6h 40m — under your 7h 30m target',s:'Wellness · from check-in',when:'8h',icon:'moon',tone:'info',unread:true},
 {t:'Netflix renews in 3 days (₹649)',s:'Subscriptions',when:'Yesterday',icon:'repeat',tone:''},
 {t:'AI: 3 tasks were rescheduled from Monday',s:'Review the changes in AI activity',when:'Yesterday',icon:'sparkles',tone:'accent'},
 {t:'Water: 3 glasses to go',s:'Wellness · daily target',when:'Sun',icon:'droplet',tone:'info'},
 {t:'Goal check-in: Run a 10K',s:'Review date reached',when:'Sat',icon:'target',tone:''},
];


export const inr = (n: number) => '₹' + Math.abs(n).toLocaleString('en-IN');
export const PRIORITY = [TASKS[0], TASKS[1], TASKS[5]];
export const WELL: [string, string, string, string, string][] = [['moon','Sleep','6h 40m','target 7h 30m','info'],['activity','Steps','6,210','of 8,000',''],['droplet','Water','5 / 8','glasses','info'],['scale','BMI','23.6','normal · 72.4 kg','']];
const bmiW = 72.4, bmiH = 175; const bmiV = +(bmiW / ((bmiH / 100) ** 2)).toFixed(1);
export const BMI = { w: bmiW, h: bmiH, v: bmiV.toFixed(1), band: bmiV < 18.5 ? 'Under' : bmiV < 25 ? 'Normal range' : bmiV < 30 ? 'Over' : 'High' };
export const NAV_BADGE: Record<string, number> = { tasks: 5 }; // open tasks due today (matches the 2/7 stat)
export const UNREAD = NOTIFS.filter((n) => n.unread).length;
