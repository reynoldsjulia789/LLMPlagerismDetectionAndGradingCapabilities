import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 2, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
public class Hw1Test
{
    private Hw1ListAdapter sut1;
    private Hw1ListAdapter sut2;
    private Hw1ListAdapter sut3;

    @BeforeEach
    public void setup()
    {
        sut1 = new Hw1ListAdapter();
        sut2 = new Hw1ListAdapter();
        sut3 = new Hw1ListAdapter();

        sut1.addFirst(null);
        sut1.addFirst("bad");
        sut1.addFirst("apple");
        sut1.addFirst(null);

        sut2.addFirst("6:Tony");
        sut2.addFirst("5:Tom");
        sut2.addFirst("4:Tim");
        sut2.addFirst("3:Abby");
        sut2.addFirst("2:Morning");
        sut2.addFirst("1:Good");
    }

    @Test
    public void testAddFirst()
    {
        assertTrue(sut3.isEmpty());
        assertEquals(6, sut2.size());
        assertEquals(4, sut1.size());

        assertEquals("1:Good", sut2.get(0));
        assertEquals("2:Morning", sut2.get(1));
        assertEquals("3:Abby", sut2.get(2));

        assertNull(sut1.get(0));
        assertEquals("apple", sut1.get(1));
        assertEquals("bad", sut1.get(2));
    }

    @Test
    public void testAddLast()
    {
        sut3.add("A");
        assertEquals("A", sut3.get(0));

        sut3.add("B");
        assertEquals("B", sut3.get(1));

        sut3.add(null);
        assertNull(sut3.get(2));

        sut3.add("C");
        assertEquals("C", sut3.get(3));
    }

    @Test
    public void testAddIndex()
    {
        sut1.add(0, 0);
        assertEquals(0, sut1.get(0));

        sut1.add(5, 5);
        assertEquals(5, sut1.get(5));

        sut1.add(3, 3);
        assertEquals(3, sut1.get(3));

        sut3.add(0, 0);
        assertEquals(0, sut3.get(0));

        assertThrows(Exception.class, () -> sut3.add(2, 2));
    }

    @Test
    public void testRemoveIndex()
    {
        Object removed = sut1.remove(0);
        assertNull(removed);

        removed = sut1.remove(2);
        assertNull(removed);

        assertThrows(Exception.class, () -> sut1.remove(2));

        removed = sut1.remove(1);
        assertEquals("bad", removed);

        removed = sut1.remove(0);
        assertEquals("apple", removed);
    }

    @Test
    public void testGetIndex()
    {
        assertNull(sut1.get(0));
        assertEquals("apple", sut1.get(1));
        assertEquals("bad", sut1.get(2));
        assertNull(sut1.get(3));
        assertThrows(Exception.class, () -> sut1.get(4));
    }

    @Test
    public void testContains()
    {
        assertTrue(sut1.contains(null));

        assertTrue(sut2.contains("1:Good"));
        assertTrue(sut2.contains("6:Tony"));
        assertFalse(sut2.contains("notexist"));
        assertFalse(sut2.contains(""));
        assertFalse(sut2.contains(null));

        assertFalse(sut3.contains("notexist"));
        assertFalse(sut3.contains(null));
    }

    @Test
    public void testRemoveObject()
    {
        sut2.remove("1:Good");
        assertEquals("{2:Morning->3:Abby->4:Tim->5:Tom->6:Tony}", sut2.toString());

        sut2.remove("notexist");
        assertEquals("{2:Morning->3:Abby->4:Tim->5:Tom->6:Tony}", sut2.toString());

        sut2.remove("6:Tony");
        assertEquals("{2:Morning->3:Abby->4:Tim->5:Tom}", sut2.toString());

        sut2.remove(null);
        assertEquals("{2:Morning->3:Abby->4:Tim->5:Tom}", sut2.toString());

        sut2.remove("4:Tim");
        assertEquals("{2:Morning->3:Abby->5:Tom}", sut2.toString());

        sut1.remove(null);
        assertEquals("{apple->bad->null}", sut1.toString());

        sut1.remove(null);
        assertEquals("{apple->bad}", sut1.toString());

        sut3.remove("notexist");
        assertEquals("{}", sut3.toString());
    }

    @Test
    public void testRemoveFirst()
    {
        try
        {
            assertNull(sut1.removeFirst());
            assertEquals("{apple->bad->null}", sut1.toString());

            assertEquals("apple", sut1.removeFirst());
            assertEquals("bad", sut1.removeFirst());
            assertNull(sut1.removeFirst());
        }
        catch (Exception caught)
        {
            fail();
        }

        assertThrows(Exception.class, () -> sut1.removeFirst());
    }

    @Test
    public void testRemoveAllCopies()
    {
        sut3.addFirst("A");
        sut3.addFirst("A");
        sut3.addFirst("A");
        sut3.addFirst("B");

        sut3.removeAllCopies("notexist");
        assertEquals("{B->A->A->A}", sut3.toString());

        sut3.removeAllCopies("B");
        assertEquals("{A->A->A}", sut3.toString());

        sut3.removeAllCopies("A");
        assertEquals("{}", sut3.toString());

        sut3.addFirst("A");
        sut3.addFirst(null);
        sut3.addFirst(null);

        sut3.removeAllCopies(null);
        assertEquals("{A}", sut3.toString());
    }

    @Test
    public void testInterleave()
    {
        Hw1ListAdapter result = Hw1ListAdapter.interleave(sut1, sut2);
        assertNotNull(result);
        assertEquals("{null->1:Good->apple->2:Morning->bad->3:Abby->null->4:Tim->5:Tom->6:Tony}", result.toString());

        result = Hw1ListAdapter.interleave(sut1, sut3);
        assertNotNull(result);
        assertEquals("{null->apple->bad->null}", result.toString());
    }
}
