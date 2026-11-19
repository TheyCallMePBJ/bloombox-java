package bloombox.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void parsesMpvPropertyChangeEvent() {
        Object v = Json.parse("{\"event\":\"property-change\",\"id\":1,\"name\":\"time-pos\",\"data\":12.5}");
        assertEquals(Map.of("event", "property-change", "id", 1L, "name", "time-pos", "data", 12.5), v);
    }

    @Test
    void roundTripsCommandWithAwkwardPath() {
        String path = "/music/AC\\DC/\"Back\" in Black\n.flac";
        String out = Json.write(Map.of("command", List.of("loadfile", path, "replace")));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) Json.parse(out);
        assertEquals(List.of("loadfile", path, "replace"), back.get("command"));
    }

    @Test
    void parsesLiteralsNestedArraysAndUnicodeEscapes() {
        assertEquals(List.of(true, false, List.of(), "é"), Json.parse("[true, false, [], \"\\u00e9\"]"));
        assertNull(Json.parse("null"));
        assertEquals(-3L, Json.parse("-3"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"open"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
    }

    @Test
    void emptyContainers() {
        assertEquals(Map.of(), Json.parse("{ }"));
        assertEquals(List.of(), Json.parse("[ ]"));
    }
}
