# Usage (from common/src/main/resources/assets/mca): python3 ../../../../../../scripts/gen_talk.py lang settlements/talk_variants.json
# Writes the villagers' conversations into ru_ru / en_us and talk_variants.json.
# A conversation is four lines: a and c said by the one who starts it, b and d by the other.
# {masc|fem} in a Russian line: the speaker's own grammar (the ".f" twin of the key is the woman's).
import json, re, sys, collections

LANG = sys.argv[1]  # .../assets/mca/lang
OUT_VARIANTS = sys.argv[2]

# topic -> list of conversations; each conversation = list of (ru, en)
T = collections.OrderedDict()

def conv(topic, *lines):
    T.setdefault(topic, []).append(lines)

# ---------- the weather and the hour ----------
conv("rain",
     ("Опять льёт как из ведра.", "Pouring again."),
     ("Зато огороды напьются.", "The gardens will drink, at least."),
     ("Только бы крышу не протекло.", "As long as the roof holds."),
     ("Твою-то? Её давно латать пора.", "Yours? It wants patching anyway."))
conv("rain",
     ("{Промок|Промокла} до нитки, пока от поля {дошёл|дошла}.", "Soaked through just walking back from the field."),
     ("Посиди у огня, обсохнешь.", "Sit by the fire, you'll dry."),
     ("А работа сама себя сделает?", "And the work does itself?"),
     ("Работа никуда не денется. Простынешь — вот тогда беда.", "The work will keep. Catch a chill and then you'll see trouble."))
conv("rain",
     ("Слышишь, как по крыше стучит?", "Hear it drumming on the roof?"),
     ("Под такой стук и спится хорошо.", "Good sleeping weather, that."),
     ("Тебе бы только спать.", "You'd sleep through anything."),
     ("А что ещё делать, когда льёт?", "What else is there to do when it pours?"))
conv("storm",
     ("Ну и гроза... Аж стены дрожат.", "What a storm... the walls are shaking."),
     ("Лишь бы в амбар не ударило.", "Pray it doesn't strike the barn."),
     ("Дети где? Все дома?", "Where are the children? All indoors?"),
     ("Дома, дома. Под одеялом сидят.", "Indoors. Hiding under the blankets."))
conv("storm",
     ("Видишь, как над лесом сверкает?", "See it flashing over the woods?"),
     ("Лесорубу сегодня лучше дома посидеть.", "The lumberjack had better stay home today."),
     ("Скажи ему [сам|сама].", "You tell him."),
     ("Скажу. Он меня хоть иногда слушает.", "I will. He listens to me, now and then."))
conv("cold",
     ("Ну и мороз сегодня.", "Bitter cold today."),
     ("Дров бы побольше на зиму.", "We'll want more firewood for winter."),
     ("Лесоруб и так с ног валится.", "The lumberjack's run off his feet as it is."),
     ("Тогда сами пойдём, руки не отвалятся.", "Then we'll go ourselves. Our hands won't fall off."))
conv("cold",
     ("Пальцы совсем не гнутся.", "My fingers won't bend."),
     ("Подыши на них. И варежки надень.", "Breathe on them. And put your mittens on."),
     ("Варежки я ещё осенью {потерял|потеряла}.", "I lost my mittens back in the autumn."),
     ("Возьми мои. У меня вторые есть.", "Take mine. I've a spare pair."))
conv("morning",
     ("Доброе утро. [Выспался|Выспалась]?", "Morning. Sleep well?"),
     ("Какое там. Петух под окном орал.", "Hardly. The rooster was yelling under my window."),
     ("Это не петух, это сосед храпит.", "That's not the rooster, that's the neighbour snoring."),
     ("Тогда ещё хуже. Петуха хоть сварить можно.", "Worse, then. At least you can cook a rooster."))
conv("morning",
     ("Туман-то какой с утра.", "Thick fog this morning."),
     ("К полудню разойдётся, будет жарко.", "It'll burn off by noon. Hot day ahead."),
     ("Значит, работать надо, пока прохладно.", "Then we work while it's cool."),
     ("Так и сделаем. Только чаю сначала.", "Right. Tea first, though."))
conv("morning",
     ("Что сегодня делаешь?", "What are you on today?"),
     ("То же, что вчера. И что завтра буду.", "Same as yesterday. Same as tomorrow."),
     ("Зато без дела не сидишь.", "Well, you're never idle."),
     ("Это точно. Ну, пошли, а то старейшина ворчать будет.", "That's true. Come on, or the elder will grumble."))
conv("evening",
     ("Вот и день прошёл.", "Another day gone."),
     ("Хороший был день.", "A good day."),
     ("Лишь бы и завтра такой.", "Let's hope tomorrow's the same."),
     ("Будет. Если дождя не нагонит.", "It will. Unless the rain comes."))
conv("evening",
     ("{Устал|Устала} я сегодня, сил нет.", "Worn out today, no strength left."),
     ("Посидим у костра, отойдёшь.", "Sit by the fire a while, you'll come round."),
     ("Только недолго. Завтра опять с рассвета.", "Not long. Up at dawn again tomorrow."),
     ("Завтра и подумаем. Сейчас — костёр.", "Tomorrow can wait. The fire's here now."))
conv("evening",
     ("Закат-то какой красный.", "Look how red the sunset is."),
     ("К ветру. Или к хорошей погоде, кто его знает.", "Means wind. Or fine weather. Who knows."),
     ("Ты каждый раз так говоришь.", "You say that every time."),
     ("И каждый раз угадываю. Наполовину.", "And every time I'm half right."))
conv("night",
     ("Ты чего не спишь?", "Why aren't you asleep?"),
     ("Не спится. Всё про урожай думаю.", "Can't sleep. Thinking about the harvest."),
     ("Думай не думай, а пшеница быстрее не вырастет.", "Thinking won't make the wheat grow faster."),
     ("Это верно. Ладно, пойду лягу.", "True enough. I'll go and lie down."))
conv("night",
     ("Слышишь? Вроде кто-то за оградой ходит.", "Hear that? Something walking past the fence."),
     ("Это стража обход делает.", "That's the watch on its round."),
     ("А если не стража?", "And if it isn't?"),
     ("Тогда тем более сиди дома.", "All the more reason to stay inside."))
conv("restday",
     ("Хорошо, что сегодня выходной.", "Good thing it's a day off."),
     ("Ага. Хоть отосплюсь.", "Yes. I'll finally catch up on sleep."),
     ("А я на рыбалку собираюсь.", "I'm off fishing."),
     ("Принеси и на мою долю, если клевать будет.", "Bring some for me too, if they're biting."))
conv("restday",
     ("Чем займёшься сегодня?", "What'll you do today?"),
     ("Ничем. Совсем ничем. Буду сидеть и смотреть на облака.", "Nothing. Nothing at all. I'll sit and watch the clouds."),
     ("Завидую. А мне забор чинить.", "I envy you. I've a fence to mend."),
     ("В выходной? Брось, завтра починишь.", "On a day off? Leave it, tomorrow will do."))
conv("hungry",
     ("Живот к спине прилип...", "My stomach's stuck to my back..."),
     ("Пойдём к кухарке, может, что осталось.", "Let's see if the cook has anything left."),
     ("Я вчера одну корку хлеба {съел|съела}.", "All I had yesterday was a crust of bread."),
     ("Держись. Ягоды в лесу поспели, сходим.", "Hold on. The berries are ripe in the woods, we'll go."))
conv("hungry",
     ("В кладовой пусто, хоть шаром покати.", "The larder's bare as a bone."),
     ("Фермер говорит, скоро жать будут.", "The farmer says they'll be reaping soon."),
     ("Скоро — это когда?", "Soon — and when's that?"),
     ("Когда поспеет. Потерпи.", "When it's ripe. Be patient."))

# ---------- friends and strangers ----------
conv("friends",
     ("Давно мы с тобой так не сидели.", "Been a while since we sat like this."),
     ("Всё работа да работа.", "Always work, work."),
     ("Помнишь, как мы детьми на речку бегали?", "Remember running to the river as children?"),
     ("Помню. Ты тогда ещё в воду [свалился|свалилась].", "I do. You fell straight in."))
conv("friends",
     ("Спасибо, что вчера [помог|помогла] с крышей.", "Thanks for helping with the roof yesterday."),
     ("Да брось. Ты бы для меня то же [сделал|сделала].", "Oh, come on. You'd have done the same."),
     ("{Сделал|Сделала} бы. И сделаю, если надо.", "I would. And I will, if you need it."),
     ("Вот и договорились.", "Then that's settled."))
conv("friends",
     ("Слушай, мне посоветоваться надо.", "Listen, I need your advice."),
     ("Говори, я слушаю.", "Go on, I'm listening."),
     ("Не знаю, переезжать в новый дом или нет.", "I can't decide whether to move to the new house."),
     ("Переезжай. Там хоть крыша не течёт.", "Move. At least that roof doesn't leak."))
conv("acquaint",
     ("Мы, кажется, толком и не знакомы.", "I don't think we've properly met."),
     ("И правда. Я тут недавно.", "No, we haven't. I'm new here."),
     ("Ну, добро пожаловать. Если что — спрашивай.", "Well, welcome. If you need anything, just ask."),
     ("Спасибо. Обязательно.", "Thank you. I will."))
conv("acquaint",
     ("Ты ведь в доме у колодца живёшь?", "You live in the house by the well, don't you?"),
     ("Нет, это сосед. Я подальше, у поля.", "No, that's my neighbour. I'm further out, by the field."),
     ("А, {перепутал|перепутала}. Извини.", "Ah, I mixed you up. Sorry."),
     ("Ничего. Нас тут все путают.", "No harm. Everyone mixes us up."))

# ---------- small talk ----------
conv("small.0",
     ("Как думаешь, будет дождь?", "Think it'll rain?"),
     ("Колени ноют — будет.", "My knees ache. It will."),
     ("Твои колени ещё ни разу не ошиблись.", "Your knees have never been wrong yet."),
     ("Вот и я им верю больше, чем небу.", "So I trust them more than the sky."))
conv("small.1",
     ("Вчера странного путника {видел|видела}.", "Saw a strange traveller yesterday."),
     ("Опять того, в зелёном?", "The one in green again?"),
     ("Он у костра про драконов рассказывал.", "He was telling dragon stories by the fire."),
     ("Ну-ну. Меньше бы [слушал|слушала] байки.", "Sure. You should listen to fewer tales."))
conv("small.3",
     ("Кто опять оставил ворота открытыми?", "Who left the gate open again?"),
     ("Не я, честно.", "Not me, honest."),
     ("Так ты [последним выходил|последней выходила].", "You were the last one out."),
     ("Тогда, может, и я...", "Then maybe it was me..."))
conv("small.4",
     ("Слышишь, ночью кто-то выл?", "Did you hear the howling last night?"),
     ("Волки. Или кто похуже.", "Wolves. Or something worse."),
     ("Я вчера факел всю ночь не {тушил|тушила}.", "I kept a torch burning all night."),
     ("И правильно. Свет они не любят.", "Good. They don't like the light."))
conv("small.6",
     ("Хорошо у нас всё-таки.", "It's good here, really."),
     ("Да, спокойно. Пока что.", "Yes, quiet. For now."),
     ("Лишь бы урожай удался.", "If only the harvest comes good."),
     ("Удастся. Земля тут добрая.", "It will. The soil's kind here."))
conv("small.7",
     ("Дети опять в шахту лазили.", "The children were down the mine again."),
     ("Надо им строго сказать.", "Someone needs to speak to them."),
     ("Я их вчера оттуда за уши {вытащил|вытащила}.", "I dragged them out by the ears yesterday."),
     ("Вот и правильно. Шахта — не игрушка.", "Good. A mine's no plaything."))
conv("small.2",
     ("Скорее бы праздник урожая.", "I can't wait for the harvest feast."),
     ("И не говори, весь год жду.", "Nor me, I wait for it all year."),
     ("В этот раз я пирог испеку. Большой.", "This time I'll bake a pie. A big one."),
     ("Только чтобы не как в прошлый раз, угольком.", "Just not like last time. Charcoal."))
conv("small.5",
     ("Мне бы дом побольше.", "I could do with a bigger house."),
     ("Попроси строителей, может, надстроят.", "Ask the builders. They might add a floor."),
     ("Они и так без передышки работают.", "They're working flat out as it is."),
     ("Тогда подожди. Всему своё время.", "Then wait. All in good time."))
conv("small.8",
     ("Ты кошку мою не [видел|видела]?", "Have you seen my cat?"),
     ("Рыжую? На крыше у кузницы сидела.", "The ginger one? Sitting on the forge roof."),
     ("Опять. Она там греется.", "Again. She likes the warmth up there."),
     ("Умная кошка. Умнее некоторых.", "Clever cat. Cleverer than some."))
conv("small.9",
     ("Говорят, за холмами есть другая деревня.", "They say there's another village beyond the hills."),
     ("Есть. Торговцы оттуда иногда приходят.", "There is. Traders come from there now and then."),
     ("{Хотел|Хотела} бы я туда сходить.", "I'd like to go there someday."),
     ("Сходишь. Только стражника с собой возьми.", "You will. Take a guard along, though."))
conv("small.10",
     ("Опять колодец мелеет.", "The well's running low again."),
     ("Это всё жара.", "It's the heat."),
     ("Или кто-то по ночам скотину поит.", "Or someone's watering their animals at night."),
     ("Тише ты. Не будем на соседей наговаривать.", "Hush. Let's not go blaming the neighbours."))
conv("small.11",
     ("У тебя игла найдётся? Рубаху {порвал|порвала}.", "Got a needle? I've torn my shirt."),
     ("Найдётся. И нитки дам.", "I have. And thread too."),
     ("Спасибо. С меня пирожок.", "Thanks. I owe you a pastry."),
     ("Два. Нитки хорошие.", "Two. It's good thread."))

# ---------- the work ----------
conv("work.cartographer",
     ("Ещё пару рядов, и стены готовы.", "A couple more rows and the walls are done."),
     ("Не торопись, главное — крепко.", "Don't rush. Strong is what matters."),
     ("Крепко и ровно. По-другому не строю.", "Strong and straight. I don't build any other way."),
     ("Вот за это тебя и ценят.", "That's why they value you."))
conv("work.cartographer",
     ("Опять досок не хватает.", "Short of planks again."),
     ("Лесоруб с утра в лесу, принесёт.", "The lumberjack's been in the woods since dawn. He'll bring some."),
     ("Пока несёт, я фундамент доделаю.", "While he does, I'll finish the foundation."),
     ("Вот это по-хозяйски.", "That's the way."))
conv("work.cartographer",
     ("Как думаешь, где следующий дом ставить?", "Where do you reckon the next house should go?"),
     ("За колодцем место ровное.", "There's level ground past the well."),
     ("Там улица пойдёт. На улице не строят.", "That's where the street goes. You don't build on the street."),
     ("Ну тебе виднее, ты у нас строитель.", "You know best. You're the builder."))
conv("work.fletcher",
     ("Лес всё дальше уходит, далеко ходить приходится.", "The forest keeps getting further. It's a long walk."),
     ("Так сажай новые деревья поближе.", "Then plant new trees closer in."),
     ("Сажаю. Да только растут они не за день.", "I do. But they don't grow in a day."),
     ("Ничего, через пару лет будет роща.", "Give it a couple of years and you'll have a grove."))
conv("work.fletcher",
     ("Дуб сегодня {свалил|свалила} — вот такой толщины!", "Felled an oak today — this thick!"),
     ("Небось полдня [рубил|рубила]?", "Took you half the day, I bet?"),
     ("Полдня. Зато дров на неделю.", "Half the day. But firewood for a week."),
     ("И досок строителям. Они тебе спасибо скажут.", "And planks for the builders. They'll thank you."))
conv("work.toolsmith",
     ("Топор затупился, совсем не рубит.", "My axe has gone blunt, it won't cut."),
     ("Отнеси наточить.", "Take it to be sharpened."),
     ("Некогда. Деревья сами себя не повалят.", "No time. The trees won't fell themselves."),
     ("Тупым топором ты их и до вечера не повалишь.", "With a blunt axe you won't fell them by nightfall either."))
conv("work.mason",
     ("В шахте сегодня сыро, крепь надо ставить.", "It's damp in the mine today. Needs shoring."),
     ("Береги голову там внизу.", "Mind your head down there."),
     ("Я каску из котелка {сделал|сделала}.", "I made a helmet out of a pot."),
     ("Ну, всё лучше, чем ничего.", "Well, it's better than nothing."))
conv("work.mason",
     ("Сегодня жилу {нашёл|нашла}. Железо, похоже.", "Struck a vein today. Iron, by the look of it."),
     ("Вот это да! Старейшина обрадуется.", "Well, well! The elder will be pleased."),
     ("Если не обвалится, к вечеру всё достану.", "If it doesn't cave in, I'll have it all out by evening."),
     ("Только осторожно. Железо подождёт, а ты у нас [один|одна].", "Careful, though. The iron can wait, there's only one of you."))
conv("work.farmer",
     ("Пшеница в этом году высокая.", "The wheat's tall this year."),
     ("Лишь бы зайцы не поели.", "As long as the rabbits don't get it."),
     ("Я забор {поправил|поправила}, теперь не пролезут.", "I mended the fence. They won't get through now."),
     ("Эти пролезут везде. Хитрые.", "They'll get in anywhere. Crafty things."))
conv("work.farmer",
     ("Ещё одну грядку сегодня {вскопал|вскопала}.", "Dug another bed today."),
     ("Зачем? Старые же есть.", "What for? You've got the old ones."),
     ("Деревня растёт. Ртов больше — и хлеба надо больше.", "The village is growing. More mouths, more bread."),
     ("И то верно. Хлеб всему голова.", "True. Bread comes first."))
conv("work.farmer",
     ("Земля сохнет, полить бы.", "The soil's drying out. Needs watering."),
     ("Дождь обещали.", "Rain's on the way, they say."),
     ("Кто обещал?", "Who says?"),
     ("Колени у соседа. Они ещё не ошибались.", "The neighbour's knees. They've never been wrong."))
conv("work.guard",
     ("Ночью у шахты что-то шуршало.", "Something was rustling by the mine last night."),
     ("Посмотри там утром с факелом.", "Take a torch and look in the morning."),
     ("Посмотрю. Если что — позову ребят.", "I will. If there's trouble, I'll call the others."),
     ("Только один не лезь. Мы тебя ценим.", "Don't go alone. We need you."))
conv("work.guard",
     ("Опять всю ночь на посту.", "Another night on watch."),
     ("Спасибо тебе. Мы спим спокойно.", "Thank you. We sleep easy because of you."),
     ("Хоть кто-то спасибо говорит.", "Someone says thanks, at least."),
     ("Все говорят. Просто не вслух.", "Everyone does. Just not out loud."))
conv("work.elder",
     ("Деревня растёт, скоро новую улицу закладывать.", "The village is growing. We'll lay out a new street soon."),
     ("Только бы на всех хватило еды.", "As long as there's food enough for all."),
     ("Хватит. Фермер новые поля распахал.", "There will be. The farmer's ploughed new fields."),
     ("Тогда я {спокоен|спокойна}.", "Then I'm at ease."))

# ---------- the stranger (the player) ----------
conv("player.near",
     ("Видишь путника? Кто такой, не знаешь?", "See that traveller? Know who it is?"),
     ("Говорят, %s. Вроде не злой.", "%s, they say. Seems harmless."),
     ("Посмотрим. Глаз да глаз.", "We'll see. Keep an eye out."),
     ("Ты на всех так смотришь.", "You look at everyone like that."))
conv("player.newcomer",
     ("Новенький у нас. %s зовут.", "We've a newcomer. Goes by %s."),
     ("Надолго, интересно?", "Here to stay, I wonder?"),
     ("Кто знает. Может, и останется.", "Who knows. Maybe."),
     ("Лишь бы с добром пришёл.", "As long as it's with good intent."))
conv("player_good",
     ("%s опять нам помогает.", "%s is helping us out again."),
     ("Золото, а не человек.", "Worth their weight in gold."),
     ("Надо бы отблагодарить как-то.", "We ought to thank them somehow."),
     ("Пирогом. Пирогом всегда можно.", "With a pie. A pie always works."))
conv("player_bad",
     ("Опять %s тут ходит.", "%s is hanging about again."),
     ("Я уже сундук на замок {закрыл|закрыла}.", "I've already locked my chest."),
     ("И правильно. Береженого бог бережёт.", "Quite right. Better safe than sorry."),
     ("Стража в курсе, если что.", "The watch knows, if anything happens."))

# ---------- old lines whose grammar is set right (the speaker's own gender; third persons said neutrally) ----------
FIX = {
 "news.built.a": ("Видишь? Готово: %s!", "See that? The %s is finished!"),
 "news.built.b": ("Вижу, вижу. Хорошо вышло, крепко.", "I see. Well built, solid."),
 "news.theft.a": ("Слышишь, что говорят? %s у нас ворует.", "Heard what they're saying? %s steals from us."),
 "player_good.a": ("%s сегодня опять к нам заглядывает.", "%s has been round again today."),
 "player_bad.a": ("Смотри, %s тут опять ходит.", "Look, %s is about again."),
 "player_bad.b": ("Вижу. Глаз не спускаю.", "I see. I'm keeping an eye out."),
 "news.attacked.a": ("Слышишь? На наших напали! Это %s.", "Hear that? Our people were attacked! It was %s."),
 "news.crime.theft.a": ("Говорят, %s у нас ворует.", "They say %s steals from us."),
 "news.crime.assault.a": ("На человека напали! Это %s.", "Someone was attacked! It was %s."),
 "news.crime.murder.a": ("Человека убили... Говорят, это %s.", "A man was killed... They say it was %s."),
 "news.crime.escape.a": ("Из тюрьмы сбежали! %s.", "Someone broke out of jail! %s."),
 "news.crime.stocks.a": ("Видишь, кто в колодках? %s.", "See who's in the stocks? %s."),
 "news.crime.unsolved.theft.a": ("Слышишь, у нас кто-то ворует?", "Have you heard? Someone's been stealing."),
 "news.crime.unsolved.vandalism.a": ("Кто-то нам дом разломал.", "Somebody wrecked one of our houses."),
 "news.raid_repelled.c": ("Я {видел|видела}, как стражник троих один держал!", "I saw one guard hold off three of them!"),
 "player_good.c": ("В прошлый раз мне кирку подарили.", "Last time I was given a pickaxe."),
 "player_bad.c": ("Говорят, у соседей тоже что-то пропало.", "They say things went missing at the neighbours' too."),
 "work.cartographer.d": ("Ровно, ровно. [Сам|Сама]-то когда последний раз [строил|строила]?", "Straight, straight. When did you last build anything?"),
 "work.toolsmith.d": ("Новое. {Сам вытесал|Сама вытесала}, из ясеня.", "New. Carved it myself, out of ash."),
 "work.cleric.c": ("Опять со строительных лесов кто-то упал?", "Someone fall off the scaffolding again?"),
 "small.1.c": ("Он у костра про драконов рассказывал.", "He was telling dragon stories by the fire."),
 "small.3.c": ("Так ты [последним выходил|последней выходила].", "You were the last one out."),
 "small.4.a": ("Слышишь, ночью кто-то выл?", "Did you hear the howling last night?"),
 "small.4.c": ("Я вчера факел всю ночь не {тушил|тушила}.", "I kept a torch burning all night."),
 "small.7.c": ("Я их вчера оттуда за уши {вытащил|вытащила}.", "I dragged them out by the ears yesterday."),
 "news.crime.theft.c": ("Я в лицо {запомнил|запомнила}.", "I'd know that face anywhere."),
 "news.crime.vandalism.c": ("Вот пусть стену назад и кладёт.", "Let them lay that wall back themselves."),
 "news.crime.harassment.c": ("Ко мне вчера тоже приставали.", "They were bothering me too yesterday."),
 "gossip.lazy.a": ("%s опять полдня у костра — и ни с места.", "%s spent half the day by the fire again, not budging."),
 "gossip.hard.a": ("%s до темноты работает, ты видишь?", "%s works till dark, have you noticed?"),
 "gossip.hard.b": ("Вижу. Так и надорваться можно.", "I have. That's how people wear themselves out."),
 "gossip.grumpy.a": ("%s на меня с утра рявкает.", "%s has been snapping at me since morning."),
 "gossip.master.a": ("Посмотри, как %s работает. Залюбуешься.", "Look how %s works. A joy to watch."),
 "gossip.plain.c": ("Давно у колодца не видно.", "Haven't seen them at the well for a while."),
 "player.near.c": ("Может, торговать к нам.", "Maybe here to trade."),
 "news.player_brought.a": ("Слышишь? Нам материалы привезли! От %s.", "Heard? Someone brought us materials! %s did."),
 "news.player_brought.b": ("Слышу. Хороший человек, не то что некоторые.", "I have. A good sort, not like some."),
 "hungry.c": ("Я вчера одну корку хлеба {съел|съела}.", "All I had yesterday was a crust of bread."),
 "news.repaired.d": ("Я уже со счёта {сбился|сбилась}.", "I've lost count."),
 "need.cobble.b": ("Шахтёр обещал накопать.", "The miner promised to dig some."),
}
# one-liners (settlements.say.*) said in the speaker's grammar
SAY_FIX = {
 "eat": "{Проголодался|Проголодалась}, пора поесть.",
 "tavern.go": "{Проголодался|Проголодалась}, иду в таверну.",
 "haul.done": "{Сложил|Сложила} всё на склад.",
 "starving.4": "{Отдал|Отдала} бы всё за кусок хлеба.",
 "weary": "{Устал|Устала} я сегодня.",
 "tool.craft": "{Сделал|Сделала} себе новый: %s.",
 "oops.2": "Не то {взял|взяла}. Сейчас.",
 "way.round": "Ну и дорога... кое-как {добрался|добралась}.",
 "way.round.1": "Фух, {обошёл|обошла}.",
 "way.round.2": "Еле {пробрался|пробралась}.",
 "ask.cobble": "%s, камня нет! Неси, что {накопал|накопала}.",
 "hired.cartographer": "Старейшина {назначил|назначила} меня строителем.",
}

def forms(ru):
    """{masc|fem}: the speaker's gender; [masc|fem]: the one spoken to. -> {suffix: text} for '', '.f', '.lf', '.f.lf'."""
    out = {}
    for sf, speaker in (("", 0), (".f", 1)):
        for lf, listener in (("", 0), (".lf", 1)):
            t = re.sub(r"\{([^|}]*)\|([^}]*)\}", lambda m: m.group(1 + speaker), ru)
            t = re.sub(r"\[([^|\]]*)\|([^\]]*)\]", lambda m: m.group(1 + listener), t)
            out[sf + lf] = t
    return out

def load(name):
    p = f"{LANG}/{name}.json"
    return p, json.load(open(p, encoding="utf-8"), object_pairs_hook=collections.OrderedDict)

pr, ru = load("ru_ru")
pe, en = load("en_us")

variants = {}
for topic, convs in T.items():
    base = f"settlements.talk.{topic}"
    for i, c in enumerate(convs):
        idx = i  # the first one written here takes the old one's place (set right), the rest are more ways it goes
        prefix = base if idx == 0 else f"{base}.v{idx}"
        for j, (r, e) in enumerate(c):
            key = f"{prefix}.{'abcd'[j]}"
            for suf, t in forms(r).items():
                ru[key + suf] = t
                en[key + suf] = e
    n = len(convs) - 1
    if n > 0:
        variants[topic] = max(variants.get(topic, 0), n)

for k, (r, e) in FIX.items():
    key = f"settlements.talk.{k}"
    for suf, t in forms(r).items():
        ru[key + suf] = t
        en[key + suf] = e

for k, r in SAY_FIX.items():
    key = f"settlements.say.{k}"
    fs = forms(r)
    ru[key] = fs[""]
    ru[key + ".f"] = fs[".f"]

# every spoken line has its other forms (the same words where nothing changes): a one-liner its woman's twin, a
# conversation line all four (who speaks, to whom)
for d in (ru, en):
    for key in list(d.keys()):
        if key.endswith(".f") or key.endswith(".lf"):
            continue
        if key.startswith("settlements.say.") or key.startswith("settlements.react."):
            d.setdefault(key + ".f", d[key])
        if key.startswith("settlements.talk."):
            for suf in (".f", ".lf", ".f.lf"):
                d.setdefault(key + suf, d[key])

for p, d in ((pr, ru), (pe, en)):
    json.dump(d, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(p, "a").write("\n")
json.dump(variants, open(OUT_VARIANTS, "w"), indent=1, sort_keys=True)
total = sum(len(v) for v in T.values())
print("conversations written:", total, "topics with variants:", len(variants))
