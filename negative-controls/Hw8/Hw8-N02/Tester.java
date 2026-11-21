/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Builds two lists with the same random values, sorts one with each algorithm,
 * and reports time, sortedness and size.
 */
import java.util.Random;

public class Tester {

    private static final int N = 20000;
    private static final int MAX_VALUE = 3000000;

    public static void main(String[] args) {
        LinkedList<Integer> A = new LinkedList<>();
        LinkedList<Integer> A2 = new LinkedList<>();
        Random rng = new Random();
        for (int i = 0; i < N; i++) {
            Integer x = rng.nextInt(MAX_VALUE);
            A.addLast(x);
            A2.addLast(x);
        }

        report("mergesort", A, A::MergeSort);
        System.out.println("Size of list A is: " + A.getSize());
        report("insertionsort", A2, A2::InsertionSort);
        System.out.println("Size of list A2 is: " + A2.getSize());
    }

    private static void report(String name, LinkedList<Integer> list, Runnable sort) {
        double then = System.currentTimeMillis();
        sort.run();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for " + name + " " + (now - then));
        System.out.println(list.isSorted());
    }
}
