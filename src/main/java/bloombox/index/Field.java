package bloombox.index;

import java.util.Locale;

/** Searchable fields, with ranking weights (higher = more relevant match). */
public enum Field {
    TITLE(100, "title", "t"),
    ARTIST(80, "artist", "a", "ar"),
    ALBUM(60, "album", "al"),
    LYRICS(25, "lyrics", "lyric", "l");

    public final int weight;
    public final int bit;
    private final String[] names;

    Field(int weight, String... names) {
        this.weight = weight;
        this.bit = 1 << ordinal();
        this.names = names;
    }

    /** Field for a query prefix such as "artist" or "a", or null. */
    public static Field byName(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        for (Field f : values()) for (String alias : f.names) if (alias.equals(n)) return f;
        return null;
    }

    public String label() {
        return names[0];
    }
}
