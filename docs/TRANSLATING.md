# Translating Aura

Aura shows every word in the TV's own language: change the language in Android's settings and
the home screen is built again in it. A language Aura doesn't have yet falls back to English.

Today Aura speaks **English**, **German**, **Spanish** and **Portuguese**. Corrections and new
languages are very welcome. You don't need Android Studio or to know Java, only a text editor
and a GitHub account.

## Where the words are

| File | What's in it |
|---|---|
| `launcher/src/main/res/values/strings.xml` | English, the original. Every other language follows it. |
| `launcher/src/main/res/values-de/strings.xml` | German |
| `launcher/src/main/res/values-es/strings.xml` | Spanish |
| `launcher/src/main/res/values-pt/strings.xml` | Portuguese (written as in Brazil) |

Each line is one piece of text, with a name that stays in English and the words you translate:

```xml
<string name="photo_mode">Fotomodus</string>
```

Comments such as `<!-- Control Center -->` say where on the TV the lines below them appear.

## Fixing a line

1. Open the language's `strings.xml` on GitHub and press the pencil (Edit).
2. Change only the words between `>` and `</string>`. Leave the `name` alone.
3. Propose the change. Say in a word or two what was wrong ("sounded stiff", "Fire TV calls this …").

## Adding a language

1. Copy `values/strings.xml` to a new folder named after the language's
   [two-letter code](https://en.wikipedia.org/wiki/List_of_ISO_639_language_codes):
   `values-fr`, `values-it`, `values-nl`, `values-hi`. For a country's own variant add its code:
   `values-pt-rPT` for Portugal (the plain `values-pt` is Brazilian).
2. Translate every line. A line you leave out shows in English, so a half-finished file still works.
3. If the language keeps nouns capitalised in the middle of a sentence, as German does, also copy
   `values-de/config.xml`: it stops Aura lowering words like a weather ("12° and cloudy").
4. Open a pull request, or attach the file to an issue and it will be added for you.

## Things to keep as they are

- **Placeholders** like `%1$s`, `%1$d` and `%2$s` are filled in by Aura: a name, a number, a time.
  Keep every one, exactly as written. You may move them, since the number says which is which:
  `%1$s opens %2$s` can become `%2$s öffnet sich mit %1$s`.
- **`%%`** is a percent sign. Keep it doubled.
- **Apostrophes and quotes** need a backslash: `don\'t`, `\"Allow\"`. Typographic quotes
  (`„…“`, `«…»`, `“…”`) need none.
- **Text in quotes** like `"Playing  ·  %1$s"` keeps its double spaces around the `·`; the quotes
  are what keep them. Leave the quotes and the spaces.
- **`\n`** is a new line.
- **Names**: Aura, Smart Stack, Top Shelf, tvOS, Apple TV, Google TV, Fire TV, TMDB, TVmaze,
  Unsplash, DLNA. Android's own menus (Developer options, USB debugging) are best written the way
  Android writes them in your language, so people can find them.

## Plurals

Some lines change with a number:

```xml
<plurals name="n_photos">
    <item quantity="one">%1$d Foto</item>
    <item quantity="other">%1$d Fotos</item>
</plurals>
```

Most languages need `one` and `other`. Some need more (`few`, `many`); add the ones your
language uses and Android picks the right one.

## Aura's voice

Short, plain and friendly, as if a calm friend set up the TV for you. Prefer everyday words
to technical ones, and the informal "you" where your language has one and it feels natural
(German uses *du*, Spanish *tú*, Brazilian Portuguese *você*).

A TV is read from across the room, so shorter is better. Aura shrinks a label that runs long
before it cuts it short, but a label much longer than the English may still end in "…".
Words in CAPITALS (like `CONTROL CENTER`) are small labels: keep them short and in capitals.

## Not translated

- The **problem report** stays in English: it's read by whoever fixes the problem.
- The **pages on your phone** (after scanning a code) are still English for now.
- **Famous quotes** in the screensaver come from online collections in English.
- Names of films, shows, songs and apps come from the apps and services themselves.
