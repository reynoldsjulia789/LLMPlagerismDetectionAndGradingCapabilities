/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Runs the sorting experiment: identical random input in two independent lists,
 * merge sort on one and insertion sort on the other.
 */
import java.util.Random;

public class Tester {

    public static void main(String[] args) {
        int n = 20000;
        Random random = new Random();
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = random.nextInt(3000000);
        }

        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
        for (int v : values) {
            A.addLast(v);
            A2.addLast(v);
        }

        double mergeMs = timeMillis(A::MergeSort);
        System.out.println("Time cost in milliseconds for mergesort " + mergeMs);
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.getSize());

        double insertionMs = timeMillis(A2::InsertionSort);
        System.out.println("Time cost in milliseconds for insertionsort " + insertionMs);
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());
    }

    private static double timeMillis(Runnable work) {
        double then = System.currentTimeMillis();
        work.run();
        return System.currentTimeMillis() - then;
    }
}
