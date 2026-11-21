/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Tester for the merge sort / insertion sort comparison on 20,000 random integers.
 */
import java.util.Random;

public class Tester {

    public static void main(String[] args) {
        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();

        Random generator = new Random();
        int listSize = 20000;
        for (int k = 0; k < listSize; k++) {
            Integer number = generator.nextInt(3000000);
            A.addLast(number);
            A2.addLast(number);
        }

        double t0 = System.currentTimeMillis();
        A.MergeSort();
        double t1 = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (t1 - t0));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.getSize());

        t0 = System.currentTimeMillis();
        A2.InsertionSort();
        t1 = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (t1 - t0));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());
    }
}
