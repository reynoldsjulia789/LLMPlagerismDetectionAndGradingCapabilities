import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 2, unit = TimeUnit.SECONDS)
public class Hw2Test
{
    private Hw2ListAdapter sut1;
    private Hw2ListAdapter sut2;
    private Hw2ListAdapter sut3;

    @BeforeEach
    public void setup()
    {
        sut1 = new Hw2ListAdapter();
        sut2 = new Hw2ListAdapter();
        sut3 = new Hw2ListAdapter();

        sut2.addFirst("6:Tony");
        sut2.addFirst("5:Tom");
        sut2.addFirst("4:Tim");
        sut2.addFirst("3:Abby");
        sut2.addFirst("2:Morning");
        sut2.addFirst("1:Good");

        sut1.addFirst(null);
        sut1.addFirst("bad");
        sut1.addFirst("apple");
        sut1.addFirst(null);
    }

    @Test
    public void testAddFirst()
    {
        assertEquals("{null->apple->bad->null}", sut1.toString());
        assertEquals("{1:Good->2:Morning->3:Abby->4:Tim->5:Tom->6:Tony}", sut2.toString());
        assertTrue(sut3.isEmpty());
    }

    @Test
    public void testAddLast()
    {
        sut3.addLast("A");
        assertEquals("{A}", sut3.toString());

        sut3.addLast("B");
        assertEquals("{A->B}", sut3.toString());

        sut3.addLast(null);
        assertEquals("{A->B->null}", sut3.toString());

        sut3.addLast("C");
        assertEquals("{A->B->null->C}", sut3.toString());
    }

    @Test
    public void testRemoveStartingAtBack()
    {
        sut1.addFirst("apple");

        assertFalse(sut1.removeStartingAtBack("notexist"));

        assertTrue(sut1.removeStartingAtBack("apple"));
        assertEquals("{apple->null->bad->null}", sut1.toString());

        assertTrue(sut1.removeStartingAtBack(null));
        assertEquals("{apple->null->bad}", sut1.toString());

        assertTrue(sut2.removeStartingAtBack("1:Good"));
        assertEquals("{2:Morning->3:Abby->4:Tim->5:Tom->6:Tony}", sut2.toString());
    }

    @Test
    public void testLastIndexOf()
    {
        assertEquals(-1, sut3.lastIndexOf("notexist"));
        assertEquals(3, sut1.lastIndexOf(null));
        assertEquals(-1, sut2.lastIndexOf(null));
        assertEquals(-1, sut2.lastIndexOf("notexist"));
        assertEquals(0, sut2.lastIndexOf("1:Good"));
        assertEquals(5, sut2.lastIndexOf("6:Tony"));

        sut3.addFirst("B3");
        sut3.addFirst("B3");
        sut3.addFirst("B3");
        assertEquals(2, sut3.lastIndexOf("B3"));
    }

    @Test
    public void testSubList()
    {
        sut3.addFirst("D");
        sut3.addFirst("M");
        sut3.addFirst("A");
        sut3.addFirst("B");
        sut3.addFirst("G");
        sut3.addFirst("B");
        sut3.addFirst("B");
        sut3.addFirst("F");

        assertEquals("{}", sut3.subListOfSmallerValues("A").toString());
        assertEquals("{B->B->B->A}", sut3.subListOfSmallerValues("C").toString());
        assertEquals("{F->B->B->B->A->D}", sut3.subListOfSmallerValues("G").toString());
        assertEquals("{F->B->B->G->B->A->M->D}", sut3.subListOfSmallerValues("Q").toString());
    }

    @Test
    public void testRetainAll()
    {
        sut3.retainAll(sut1);
        assertEquals("{}", sut3.toString());

        sut3.addFirst("C");
        sut3.addFirst("C");
        sut3.addFirst("6:Tony");
        sut3.addFirst("6:Tony");
        sut3.retainAll(sut2);
        assertEquals("{6:Tony->6:Tony}", sut3.toString());

        sut3 = new Hw2ListAdapter();
        sut3.addFirst(null);
        sut3.addFirst("bad");
        sut3.addFirst(null);
        sut3.addFirst("B");
        sut3.addFirst("A");
        sut3.retainAll(sut1);
        assertEquals("{null->bad->null}", sut3.toString());
    }

    @Test
    public void testSort() {
        sut3.insertionSort();
        assertEquals("{}", sut3.toString());

        sut3.addFirst("D");

        sut3.insertionSort();

        assertEquals("{D}", sut3.toString());

        sut3.addFirst("F");
        sut3.addFirst("E");
        sut3.addFirst("G");
        sut3.addFirst("E");

        sut3.insertionSort();

        assertEquals("{D->E->E->F->G}", sut3.toString());
    }

    @Test
    public void testRemoveStartingAtBackEdgeCases()
    {
        assertFalse(sut3.removeStartingAtBack("anything"));
        assertFalse(sut3.removeStartingAtBack(null));
        assertEquals("{}", sut3.toString());

        sut3.addFirst("ONLY");
        assertTrue(sut3.removeStartingAtBack("ONLY"));
        assertEquals("{}", sut3.toString());

        sut3.addLast("NEW");
        assertEquals("{NEW}", sut3.toString());
    }

    @Test
    public void testRetainAllDisjointAndEmptyEdgeCases()
    {
        sut2.retainAll(sut3);
        assertEquals("{}", sut2.toString());

        sut3.addFirst("X");
        sut3.addFirst("Y");

        sut1.retainAll(sut3);
        assertEquals("{}", sut1.toString());
    }

    @Test
    public void testSortAlreadySortedAndReverseSorted()
    {
        sut3.addLast("Z");
        sut3.addLast("M");
        sut3.addLast("A");

        sut3.insertionSort();
        assertEquals("{A->M->Z}", sut3.toString());

        sut3.insertionSort();
        assertEquals("{A->M->Z}", sut3.toString());
    }
}
