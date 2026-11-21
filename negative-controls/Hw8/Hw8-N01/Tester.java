/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Tester: sorts the same 20,000 random integers with MergeSort and InsertionSort,
 * timing both and verifying the results with isSorted().
 */
import java.util.Random;

public class Tester {

    public static void main(String[] args) {
        final int count = 20000;
        final int bound = 3000000;
        Random random = new Random();

        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(bound);
            A.addLast(value);
            A2.addLast(value);
        }

        long start = System.currentTimeMillis();
        A.MergeSort();
        long end = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (double) (end - start));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.getSize());

        start = System.currentTimeMillis();
        A2.InsertionSort();
        end = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (double) (end - start));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());
    }
}
