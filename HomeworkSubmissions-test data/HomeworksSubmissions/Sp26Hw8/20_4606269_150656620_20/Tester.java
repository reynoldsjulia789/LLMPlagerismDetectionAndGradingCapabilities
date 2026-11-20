import java.util.Random;

public class Tester {
    public static void main(String[] args) {
        final int SAMPLE_SIZE = 20000; // Use 2000 on slower machines if needed.
        final int MAX_RANDOM_VALUE = 3_000_000;

        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
        Random random = new Random();

        // Fill A and A2 with the same sequence of random values.
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            int value = random.nextInt(MAX_RANDOM_VALUE + 1);
            A.addLast(value);
            A2.addLast(value);
        }

        long mergeStart = System.currentTimeMillis();
        A.MergeSort();
        long mergeEnd = System.currentTimeMillis();
        System.out.println("A sorted with MergeSort: " + A.isSorted());
        System.out.println("MergeSort time (ms): " + (mergeEnd - mergeStart));

        long insertionStart = System.currentTimeMillis();
        A2.InsertionSort();
        long insertionEnd = System.currentTimeMillis();
        System.out.println("A2 sorted with InsertionSort: " + A2.isSorted());
        System.out.println("InsertionSort time (ms): " + (insertionEnd - insertionStart));
    }
}
