package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.request.MemoryPreamble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code <memory>} 索引的贴不贴策略：只在「历史里那份没了或者过时了」时贴全份，
 * 没变不重贴（历史里已躺着一份，再贴是白花的 token）。
 */
class MemoryPreambleTest {

    @TempDir
    Path dir;

    @Test
    void attachesOnceUntilRevisionMoves() {
        NoteBook.init(uuid -> dir, () -> 1);
        NoteBook book = NoteBook.of(UUID.randomUUID());
        book.write("base", "the furnace", "world", "at 120,64,-35");
        MemoryPreamble preamble = new MemoryPreamble(book);

        String first = preamble.next();
        assertFalse(first.isEmpty(), "第一次注入要贴全份");
        assertTrue(first.contains("base"), "索引里得有那条札记");

        assertEquals("", preamble.next(), "札记没变就不重贴");

        book.write("mine", "iron vein", "world", "north east");
        String afterWrite = preamble.next();
        assertFalse(afterWrite.isEmpty(), "她刚写过，历史里那份过时了");
        assertTrue(afterWrite.contains("mine"));
    }

    @Test
    void reattachesAfterTranscriptBoundary() {
        NoteBook.init(uuid -> dir, () -> 1);
        NoteBook book = NoteBook.of(UUID.randomUUID());
        book.write("base", "the furnace", "world", "at 120,64,-35");
        MemoryPreamble preamble = new MemoryPreamble(book);

        assertFalse(preamble.next().isEmpty(), "开局先贴一份");
        assertEquals("", preamble.next(), "没变不重贴");

        // 压缩/清空把历史里那份嚼掉了——下一次注入得重贴一份完整的
        preamble.on(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.COMPACT));
        assertFalse(preamble.next().isEmpty());
        assertEquals("", preamble.next());

        preamble.on(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.CLEAR));
        assertFalse(preamble.next().isEmpty());
    }
}
