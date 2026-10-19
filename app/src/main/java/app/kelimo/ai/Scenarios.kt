package app.kelimo.ai

/**
 * Role-play situations. [role] and [setting] are instructions for the model (English works best
 * across models); [title] and [goal] are shown to the learner.
 */
data class Scenario(
    val id: String,
    val icon: String,
    val minLevel: String,
    val title: Map<String, String>,
    val goal: Map<String, String>,
    val role: String,
    val setting: String,
)

object Scenarios {
    val free = Scenario(
        "free", "chat", "A1",
        mapOf("tr" to "Serbest sohbet", "de" to "Freies Gespräch", "en" to "Free conversation"),
        mapOf("tr" to "İstediğin konuda konuş. Hatalarını nazikçe düzeltirim.", "de" to "Sprich über alles, was du willst. Ich korrigiere sanft.", "en" to "Talk about anything. I correct gently."),
        role = "a friendly conversation partner and language tutor",
        setting = "Casual everyday conversation. Pick topics from the learner's interests. Ask one follow-up question each turn.",
    )

    val all = listOf(
        free,
        Scenario(
            "bakery", "bakery", "A1",
            mapOf("tr" to "Fırında", "de" to "In der Bäckerei", "en" to "At the bakery"),
            mapOf("tr" to "İki ekmek ve bir kahve sipariş et, fiyatı sor ve öde.", "de" to "Bestelle zwei Brötchen und einen Kaffee, frag nach dem Preis und bezahle.", "en" to "Order two rolls and a coffee, ask for the price and pay."),
            "a friendly baker behind the counter", "A small bakery in the morning. Prices: Brötchen 0,50 €, Brezel 1,20 €, Kaffee 2,40 €, Croissant 1,60 €.",
        ),
        Scenario(
            "cafe", "coffee", "A1",
            mapOf("tr" to "Kafede", "de" to "Im Café", "en" to "At the café"),
            mapOf("tr" to "Masa iste, içecek ve tatlı sipariş et.", "de" to "Frag nach einem Tisch, bestell ein Getränk und einen Kuchen.", "en" to "Ask for a table, order a drink and a cake."),
            "a waiter in a café", "A busy café in the afternoon.",
        ),
        Scenario(
            "neighbor", "home", "A1",
            mapOf("tr" to "Yeni komşu", "de" to "Neue Nachbarin", "en" to "New neighbour"),
            mapOf("tr" to "Kendini tanıt: adın, nereden geldiğin, ne iş yaptığın.", "de" to "Stell dich vor: Name, Herkunft, Beruf.", "en" to "Introduce yourself: name, origin, job."),
            "a friendly new neighbour named Frau Becker, about 60 years old", "In the staircase of an apartment building. You just moved in next door.",
        ),
        Scenario(
            "doctor", "health", "A2",
            mapOf("tr" to "Doktorda", "de" to "Beim Arzt", "en" to "At the doctor"),
            mapOf("tr" to "Şikayetlerini anlat (baş ağrısı, ateş) ve ne zamandan beri olduğunu söyle.", "de" to "Beschreibe deine Beschwerden (Kopfschmerzen, Fieber) und seit wann du sie hast.", "en" to "Describe your symptoms (headache, fever) and since when."),
            "a calm general practitioner (Hausärztin Dr. Wagner)", "A doctor's office. The patient comes in for the first time. Never give real medical diagnoses, only role-play.",
        ),
        Scenario(
            "appointment", "calendar", "A2",
            mapOf("tr" to "Telefonla randevu", "de" to "Termin am Telefon", "en" to "Booking an appointment"),
            mapOf("tr" to "Telefonda randevu al: gün, saat ve adını söyle.", "de" to "Vereinbare telefonisch einen Termin: Tag, Uhrzeit, Name.", "en" to "Book an appointment by phone: day, time, name."),
            "a receptionist at a medical practice answering the phone", "Phone call. Free slots: Tuesday 9:30, Wednesday 14:00, Friday 11:15.",
        ),
        Scenario(
            "apartment", "key", "A2",
            mapOf("tr" to "Ev görme", "de" to "Wohnungsbesichtigung", "en" to "Flat viewing"),
            mapOf("tr" to "Kira, depozito, oda sayısı ve taşınma tarihini sor.", "de" to "Frag nach Miete, Kaution, Zimmern und Einzugstermin.", "en" to "Ask about rent, deposit, rooms and move-in date."),
            "a landlord (Herr Schulz) showing an apartment", "3-room apartment, 72 m², Kaltmiete 890 €, Nebenkosten 210 €, Kaution 3 Kaltmieten, free from the 1st of next month.",
        ),
        Scenario(
            "office", "doc", "A2",
            mapOf("tr" to "Belediyede (Bürgeramt)", "de" to "Im Bürgeramt", "en" to "At the citizens' office"),
            mapOf("tr" to "İkamet adresini kaydettir (Anmeldung). Hangi belgelerin gerektiğini sor.", "de" to "Melde deine Wohnung an. Frag, welche Unterlagen du brauchst.", "en" to "Register your address. Ask which documents you need."),
            "a clerk at the Bürgeramt", "Anmeldung appointment. Required: passport or ID card, Wohnungsgeberbestätigung from the landlord. Fee: none.",
        ),
        Scenario(
            "train", "train", "A2",
            mapOf("tr" to "Tren garında", "de" to "Am Bahnhof", "en" to "At the station"),
            mapOf("tr" to "Münih'e bilet al, peronu ve kalkış saatini sor.", "de" to "Kauf ein Ticket nach München, frag nach Gleis und Abfahrt.", "en" to "Buy a ticket to Munich, ask for platform and departure."),
            "a ticket agent at the train station", "Next train to München Hbf: ICE at 10:42 from Gleis 7, 2nd class 79,90 €, change in Nürnberg not needed.",
        ),
        Scenario(
            "job", "work", "B1",
            mapOf("tr" to "İş görüşmesi", "de" to "Vorstellungsgespräch", "en" to "Job interview"),
            mapOf("tr" to "Kendini tanıt, deneyimlerini anlat ve sorular sor.", "de" to "Stell dich vor, erzähl von deiner Erfahrung und stell Fragen.", "en" to "Introduce yourself, talk about your experience, ask questions."),
            "an HR manager (Frau Klein) interviewing a candidate", "Job interview for a position in logistics. Polite 'Sie' form.",
        ),
        Scenario(
            "parents", "school", "B1",
            mapOf("tr" to "Veli toplantısı", "de" to "Elterngespräch", "en" to "Parent-teacher talk"),
            mapOf("tr" to "Çocuğunun durumu hakkında öğretmenle konuş.", "de" to "Sprich mit der Lehrerin über dein Kind.", "en" to "Talk to the teacher about your child."),
            "a primary school teacher (Frau Hoffmann)", "Parent-teacher meeting. The child is doing well in maths, needs help with reading.",
        ),
        Scenario(
            "complaint", "alert", "B1",
            mapOf("tr" to "Şikayet", "de" to "Reklamation", "en" to "Making a complaint"),
            mapOf("tr" to "Bozuk çıkan bir ürünü iade et veya değiştir.", "de" to "Reklamiere ein kaputtes Gerät und verlange Umtausch oder Geld zurück.", "en" to "Return a broken device and ask for exchange or refund."),
            "a customer service employee in an electronics store", "The customer bought a kettle 10 days ago, it no longer works. Receipt available.",
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: free
}
