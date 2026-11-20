package bloombox.bench;

import java.io.IOException;

/** One benchmark experiment: writes {@code <id>.csv} and a Markdown section to the summary. */
interface Experiment {
    String id();

    String title();

    void run(BenchContext ctx) throws IOException;
}
