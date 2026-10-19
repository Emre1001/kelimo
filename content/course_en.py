"""English starter course for Turkish and German speakers. Writes app/src/main/assets/course_en.json.

Words:     (target, tr, de)
Sentences: (target, tr, de) or (target, tr, de, [alternatives])
"""
import json, os, random

def W(t, tr, de): return {"t": t, "tr": tr, "de": de}
def S(t, tr, de, alt=None): return {"t": t, "tr": tr, "de": de, **({"alt": alt} if alt else {})}
def T(tr, en, de): return {"tr": tr, "en": en, "de": de}
def G(id, title, body, examples, keys): return {"id": id, "title": title, "body": body, "examples": examples, "keys": keys}
def L(id, title, words, sents): return {"id": id, "title": title, "words": words, "sentences": sents}
def U(id, cefr, icon, title, grammar, lessons): return {"id": id, "cefr": cefr, "icon": icon, "title": title, "grammar": grammar, "lessons": lessons}

units = [
U("e1", "A1", "chat", T("Tanışma", "Meeting people", "Kennenlernen"), [
    G("e_be", T("\"to be\": am, is, are", "\"to be\": am, is, are", "„to be“: am, is, are"),
      T("Türkçedeki \"-im, -sin, -dir\" ekleri İngilizcede \"am / is / are\" ile yapılır: I am, you are, he/she/it is, we/they are.",
        "I am, you are, he/she/it is, we/they are.", "I am, you are, he/she/it is, we/they are."),
      [S("I am a student.", "Ben öğrenciyim.", "Ich bin Student.")], ["be", "am", "is", "are", "olmak"]),
    G("e_article", T("a / an", "a / an", "a / an"),
      T("Tekil sayılabilir isimlerden önce \"a\" ya da sesli harften önce \"an\" gelir: a book, an apple. Meslekte de kullanılır: \"I am a teacher.\"",
        "Use a/an before singular countable nouns, also for jobs: \"I am a teacher.\"", "a/an vor Nomen im Singular, auch bei Berufen."),
      [S("She is an engineer.", "O bir mühendis.", "Sie ist Ingenieurin.")], ["a", "an", "article", "artikel"]),
], [
    L("e1l1", T("Merhaba", "Hello", "Hallo"), [
        W("hello", "merhaba", "hallo"), W("name", "isim", "Name"), W("friend", "arkadaş", "Freund"), W("student", "öğrenci", "Student"),
        W("teacher", "öğretmen", "Lehrer"), W("thank you", "teşekkürler", "danke"), W("please", "lütfen", "bitte"), W("goodbye", "hoşça kal", "auf Wiedersehen"),
    ], [
        S("Hello, my name is Ayşe.", "Merhaba, benim adım Ayşe.", "Hallo, ich heiße Ayşe."),
        S("What is your name?", "Adın ne?", "Wie heißt du?"),
        S("I am a student.", "Ben öğrenciyim.", "Ich bin Student."),
        S("This is my friend.", "Bu benim arkadaşım.", "Das ist mein Freund."),
        S("Nice to meet you.", "Tanıştığımıza memnun oldum.", "Freut mich."),
    ]),
    L("e1l2", T("Nerelisin?", "Where are you from?", "Woher kommst du?"), [
        W("country", "ülke", "Land"), W("city", "şehir", "Stadt"), W("live", "yaşamak", "wohnen"), W("from", "-den", "aus"),
        W("language", "dil", "Sprache"), W("speak", "konuşmak", "sprechen"), W("Turkey", "Türkiye", "Türkei"), W("Germany", "Almanya", "Deutschland"),
    ], [
        S("I am from Turkey.", "Türkiyeliyim.", "Ich komme aus der Türkei."),
        S("Where are you from?", "Nerelisin?", "Woher kommst du?"),
        S("I live in Ankara.", "Ankara'da yaşıyorum.", "Ich wohne in Ankara."),
        S("I speak Turkish and English.", "Türkçe ve İngilizce konuşuyorum.", "Ich spreche Türkisch und Englisch."),
        S("London is a big city.", "Londra büyük bir şehir.", "London ist eine große Stadt."),
    ]),
]),
U("e2", "A1", "family", T("Aile ve ev", "Family and home", "Familie und Zuhause"), [
    G("e_have", T("have / has", "have / has", "have / has"),
      T("\"sahip olmak\": I/you/we/they have, he/she/it has. Olumsuz: \"I don't have a car.\", \"She doesn't have a car.\"",
        "I/you/we/they have, he/she/it has. Negative: don't / doesn't have.", "have / has; Verneinung mit don't / doesn't."),
      [S("She has two brothers.", "İki erkek kardeşi var.", "Sie hat zwei Brüder.")], ["have", "has", "don't", "doesn't", "sahip"]),
], [
    L("e2l1", T("Ailem", "My family", "Meine Familie"), [
        W("mother", "anne", "Mutter"), W("father", "baba", "Vater"), W("brother", "erkek kardeş", "Bruder"), W("sister", "kız kardeş", "Schwester"),
        W("children", "çocuklar", "Kinder"), W("husband", "koca", "Ehemann"), W("wife", "eş (kadın)", "Ehefrau"), W("family", "aile", "Familie"),
    ], [
        S("This is my mother.", "Bu benim annem.", "Das ist meine Mutter."),
        S("I have a brother and a sister.", "Bir erkek ve bir kız kardeşim var.", "Ich habe einen Bruder und eine Schwester."),
        S("My father works in a bank.", "Babam bir bankada çalışıyor.", "Mein Vater arbeitet in einer Bank."),
        S("We have two children.", "İki çocuğumuz var.", "Wir haben zwei Kinder."),
        S("My family is very big.", "Ailem çok büyük.", "Meine Familie ist sehr groß."),
    ]),
    L("e2l2", T("Evim", "My home", "Mein Zuhause"), [
        W("house", "ev", "Haus"), W("kitchen", "mutfak", "Küche"), W("room", "oda", "Zimmer"), W("bed", "yatak", "Bett"),
        W("table", "masa", "Tisch"), W("window", "pencere", "Fenster"), W("small", "küçük", "klein"), W("big", "büyük", "groß"),
    ], [
        S("My house is small.", "Evim küçük.", "Mein Haus ist klein."),
        S("The kitchen is big.", "Mutfak büyük.", "Die Küche ist groß."),
        S("There is a table in the room.", "Odada bir masa var.", "Im Zimmer ist ein Tisch."),
        S("Please open the window.", "Lütfen pencereyi aç.", "Bitte mach das Fenster auf."),
        S("My bed is very comfortable.", "Yatağım çok rahat.", "Mein Bett ist sehr bequem."),
    ]),
]),
U("e3", "A1", "day", T("Gündelik hayat", "Daily routine", "Alltag"), [
    G("e_present", T("Geniş zaman ve -s", "Present simple and -s", "Present Simple und -s"),
      T("Alışkanlıklar için geniş zaman kullanılır. he/she/it ile fiile -s eklenir: \"She works.\" Soru ve olumsuzda do/does: \"Do you work?\", \"He doesn't work.\"",
        "Habits use the present simple; he/she/it adds -s. Questions/negatives use do/does.", "he/she/it + -s; Fragen mit do/does."),
      [S("He gets up at seven.", "Yedide kalkar.", "Er steht um sieben auf.")], ["present", "simple", "-s", "does", "geniş zaman"]),
], [
    L("e3l1", T("Günüm", "My day", "Mein Tag"), [
        W("get up", "kalkmak", "aufstehen"), W("breakfast", "kahvaltı", "Frühstück"), W("work", "çalışmak / iş", "arbeiten"), W("eat", "yemek", "essen"),
        W("drink", "içmek", "trinken"), W("sleep", "uyumak", "schlafen"), W("morning", "sabah", "Morgen"), W("evening", "akşam", "Abend"),
    ], [
        S("I get up at seven.", "Yedide kalkarım.", "Ich stehe um sieben auf."),
        S("She drinks tea in the morning.", "Sabahları çay içer.", "Sie trinkt morgens Tee.", ["In the morning she drinks tea."]),
        S("We eat dinner in the evening.", "Akşam yemeği akşam yeriz.", "Wir essen abends zu Abend."),
        S("Do you work on Saturday?", "Cumartesi çalışıyor musun?", "Arbeitest du am Samstag?"),
        S("He doesn't drink coffee.", "Kahve içmez.", "Er trinkt keinen Kaffee."),
    ]),
    L("e3l2", T("Alışveriş", "Shopping", "Einkaufen"), [
        W("shop", "dükkan", "Laden"), W("buy", "satın almak", "kaufen"), W("price", "fiyat", "Preis"), W("cheap", "ucuz", "billig"),
        W("expensive", "pahalı", "teuer"), W("money", "para", "Geld"), W("bread", "ekmek", "Brot"), W("water", "su", "Wasser"),
    ], [
        S("How much is this?", "Bu ne kadar?", "Wie viel kostet das?"),
        S("That is too expensive.", "Bu çok pahalı.", "Das ist zu teuer."),
        S("I want to buy bread.", "Ekmek almak istiyorum.", "Ich möchte Brot kaufen."),
        S("Can I pay by card?", "Kartla ödeyebilir miyim?", "Kann ich mit Karte zahlen?"),
        S("A bottle of water, please.", "Bir şişe su, lütfen.", "Eine Flasche Wasser, bitte."),
    ]),
]),
U("e4", "A2", "travel", T("Seyahat ve geçmiş", "Travel and the past", "Reisen und Vergangenheit"), [
    G("e_past", T("Geçmiş zaman: -ed ve düzensiz fiiller", "Past simple: -ed and irregular verbs", "Past Simple"),
      T("Düzenli fiiller -ed alır: worked, visited. Düzensizleri ezberle: go/went, see/saw, have/had, buy/bought. Soru ve olumsuzda \"did\" + yalın fiil: \"Did you go?\", \"I didn't go.\"",
        "Regular verbs add -ed; learn irregular ones. Questions/negatives: did + base verb.", "-ed oder unregelmäßig; Fragen mit did."),
      [S("I went to London last year.", "Geçen yıl Londra'ya gittim.", "Letztes Jahr bin ich nach London gefahren.")], ["past", "ed", "did", "went", "geçmiş"]),
], [
    L("e4l1", T("Yolculuk", "Getting around", "Unterwegs"), [
        W("train", "tren", "Zug"), W("bus", "otobüs", "Bus"), W("ticket", "bilet", "Fahrkarte"), W("station", "istasyon", "Bahnhof"),
        W("airport", "havalimanı", "Flughafen"), W("left", "sol", "links"), W("right", "sağ", "rechts"), W("far", "uzak", "weit"),
    ], [
        S("Where is the station?", "İstasyon nerede?", "Wo ist der Bahnhof?"),
        S("A ticket to Manchester, please.", "Manchester'a bir bilet, lütfen.", "Eine Fahrkarte nach Manchester, bitte."),
        S("Turn left at the corner.", "Köşeden sola dön.", "An der Ecke links abbiegen."),
        S("Is it far from here?", "Buradan uzak mı?", "Ist es weit von hier?"),
        S("I take the bus to work.", "İşe otobüsle giderim.", "Ich fahre mit dem Bus zur Arbeit."),
    ]),
    L("e4l2", T("Tatil", "Holidays", "Urlaub"), [
        W("holiday", "tatil", "Urlaub"), W("hotel", "otel", "Hotel"), W("beach", "plaj", "Strand"), W("visit", "ziyaret etmek", "besuchen"),
        W("yesterday", "dün", "gestern"), W("last year", "geçen yıl", "letztes Jahr"), W("beautiful", "güzel", "schön"), W("tired", "yorgun", "müde"),
    ], [
        S("We stayed in a nice hotel.", "Güzel bir otelde kaldık.", "Wir haben in einem schönen Hotel gewohnt."),
        S("I visited my aunt yesterday.", "Dün teyzemi ziyaret ettim.", "Ich habe gestern meine Tante besucht.", ["Yesterday I visited my aunt."]),
        S("The beach was beautiful.", "Plaj güzeldi.", "Der Strand war schön."),
        S("Did you have a good holiday?", "Tatilin güzel geçti mi?", "Hattest du einen schönen Urlaub?"),
        S("I was very tired after the trip.", "Yolculuktan sonra çok yorgundum.", "Nach der Reise war ich sehr müde."),
    ]),
]),
]

placement = [
    ("A1", "I ___ from Turkey.", ["am", "is", "are", "be"], 0),
    ("A1", "She ___ two children.", ["has", "have", "is", "haves"], 0),
    ("A1", "They ___ in London.", ["live", "lives", "living", "is live"], 0),
    ("A1", "This is ___ apple.", ["an", "a", "the a", "—"], 0),
    ("A1", "___ you like coffee?", ["Do", "Does", "Are", "Is"], 0),
    ("A1", "He ___ to work by bus.", ["goes", "go", "going", "is go"], 0),
    ("A2", "I ___ to Paris last summer.", ["went", "go", "have go", "goed"], 0),
    ("A2", "___ you see the film yesterday?", ["Did", "Do", "Have", "Were"], 0),
    ("A2", "She is ___ than her brother.", ["taller", "more tall", "tallest", "tall"], 0),
    ("A2", "We ___ dinner when the phone rang.", ["were having", "had having", "have", "are having"], 0),
    ("A2", "I've lived here ___ 2019.", ["since", "for", "from", "at"], 0),
    ("A2", "You ___ smoke here. It's not allowed.", ["mustn't", "don't have to", "needn't", "can"], 0),
    ("B1", "If it rains, we ___ at home.", ["will stay", "would stay", "stayed", "stay will"], 0),
    ("B1", "I have never ___ sushi.", ["eaten", "ate", "eat", "eating"], 0),
    ("B1", "The book ___ by millions of people.", ["was read", "read", "has reading", "is reading"], 0),
    ("B1", "She asked me where I ___.", ["lived", "live", "do live", "am living"], 0),
    ("B1", "I'm used to ___ early.", ["getting up", "get up", "got up", "have got up"], 0),
    ("B1", "If I ___ more time, I would travel.", ["had", "have", "will have", "would have"], 0),
    ("B2", "By next year, she ___ her degree.", ["will have finished", "will finish", "finishes", "has finished"], 0),
    ("B2", "I wish I ___ that.", ["hadn't said", "didn't say", "haven't said", "wouldn't say"], 0),
    ("B2", "Hardly ___ arrived when it started to rain.", ["had we", "we had", "did we", "we have"], 0),
    ("B2", "He denied ___ the money.", ["taking", "to take", "take", "to have take"], 0),
]

rng = random.Random(7)
hint = {"tr": "Doğru seçeneği seç.", "en": "Choose the right option.", "de": "Wähle die richtige Antwort."}
def shuffled(c, q, o, a):
    right = o[a]; opts = o[:]; rng.shuffle(opts)
    return {"cefr": c, "q": q, "options": opts, "answer": opts.index(right), "hint": hint}

course = {"target": "en", "version": 1, "units": units, "placement": [shuffled(*p) for p in placement], "verbs": []}
out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "course_en.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(course, f, ensure_ascii=False, separators=(",", ":"))
print(f"course_en.json: {len(units)} units, {sum(len(u['lessons']) for u in units)} lessons")
