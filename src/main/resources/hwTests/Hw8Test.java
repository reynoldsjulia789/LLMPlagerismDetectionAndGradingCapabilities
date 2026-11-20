import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public class Hw8Test
{
    private Hw8ListAdapter sut1;
    private Hw8ListAdapter sut2;

    @BeforeEach
    public void setUp()
    {
        sut1 = new Hw8ListAdapter();
        sut2 = new Hw8ListAdapter();
    }

    @Test
    public void testMergeSort20kRandomElements()
    {
        Random        random   = new Random(42);
        List<Integer> expected = new ArrayList<>();

        for (int idx = 0; idx < 20000; idx++)
        {
            int randomInt = random.nextInt(3000001); // 0 to 3,000,000 inclusive

            sut1.addLast(randomInt);
            expected.add(randomInt);
        }

        Collections.sort(expected);
        sut1.mergeSort();

        assertTrue(sut1.isSorted(), "isSorted() should return true after MergeSort on 20,000 elements.");
        assertEquals(expected, sut1.getList());
    }

    @Test
    public void testInsertionSort20kRandomElements()
    {
        Random        random    = new Random(42);
        List<Integer> expected = new ArrayList<>();

        for (int idx = 0; idx < 20000; idx++)
        {
            int randomInt = random.nextInt(3000001);

            sut2.addLast(randomInt);
            expected.add(randomInt);
        }

        Collections.sort(expected);
        sut2.insertionSort();

        assertTrue(sut2.isSorted(), "isSorted() should return true after InsertionSort on 20,000 elements.");
        assertEquals(expected, sut2.getList());
    }

    @Test
    public void testMergeSortIsFasterThanInsertionSort()
    {
        Random random = new Random(42);

        for (int idx = 0; idx < 10000; idx++)
        {
            int randomInt = random.nextInt(3000001);

            sut1.addLast(randomInt);
            sut2.addLast(randomInt);
        }

        long startMerge = System.nanoTime();
        sut1.mergeSort();
        long mergeTime = System.nanoTime() - startMerge;

        long startInsert = System.nanoTime();
        sut2.insertionSort();
        long insertTime = System.nanoTime() - startInsert;

        assertTrue(sut1.isSorted() && sut2.isSorted());
        assertTrue(mergeTime < insertTime,
                "MergeSort (" + mergeTime + " ns) should be faster than InsertionSort (" + insertTime + " ns).");
    }

    @Test
    public void testIsSortedFunctionality()
    {
        assertTrue(sut1.isSorted(), "An empty list is sorted.");

        sut1.addLast(10);
        assertTrue(sut1.isSorted(), "A single-element list is sorted.");

        sut1.addLast(20);
        sut1.addLast(20);
        sut1.addLast(35);
        assertTrue(sut1.isSorted(), "Ascending list with duplicates should return true.");

        sut1.addLast(34);
        assertFalse(sut1.isSorted(), "List with out-of-order tail element should return false.");
    }

    @Test
    public void testIsSortedTimeComplexity()
    {
        for (int idx = 0; idx < 100000; idx++)
        {
            sut1.addLast(idx);
        }

        // attempts to verify O(n) performance on 100,000 elements (should? time out if O(n^2))
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            assertTrue(sut1.isSorted());
        }, "isSorted() exceeded 500ms on 100,000 elements; likely not O(n).");
    }

    @Test
    public void testMergeSortEmptyAndSingleElement()
    {
        assertDoesNotThrow(() -> sut1.mergeSort());
        assertTrue(sut1.isSorted());
        assertEquals(List.of(), sut1.getList());

        sut1.addFirst(0);

        assertDoesNotThrow(() -> sut1.mergeSort());
        assertTrue(sut1.isSorted());
        assertEquals(List.of(0), sut1.getList());
    }

    @Test
    public void testInsertionSortEmptyAndSingleElement()
    {
        assertDoesNotThrow(() -> sut1.insertionSort());
        assertTrue(sut1.isSorted());
        assertEquals(List.of(), sut1.getList());

        sut1.addFirst(0);

        assertDoesNotThrow(() -> sut1.insertionSort());
        assertTrue(sut1.isSorted());
        assertEquals(List.of(0), sut1.getList());
    }

    @Test
    public void testMergeSortWithDuplicatesAndNegatives()
    {
        List<Integer> values = new ArrayList<Integer>(List.of(5, -10, 5, 0, -10, 3, 5));

        for (int value : values)
        {
            sut1.addLast(value);
        }

        sut1.mergeSort();
        assertTrue(sut1.isSorted());
        assertEquals(values, sut1.getList());
    }

    @Test
    public void testInsertionSortReverseOrdered()
    {
        for (int idx = 10; idx >= 1; idx--)
        {
            sut1.addLast(idx);
        }

        sut1.insertionSort();
        assertTrue(sut1.isSorted());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), sut1.getList());
    }

    @Test
    public void testListIntegrityAfterSorting() {
        sut1.addLast(30);
        sut1.addLast(10);
        sut1.addLast(20);
        sut1.mergeSort();

        sut1.addLast(40);
        sut1.addFirst(5);

        assertTrue(sut1.isSorted());
        assertEquals(List.of(5, 10, 20, 30, 40), sut1.getList());
    }
}