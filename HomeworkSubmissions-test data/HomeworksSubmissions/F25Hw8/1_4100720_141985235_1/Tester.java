import java.util.Random;

public class Tester {
    public static void main(String[] args) {

        CDoublyLinkedList A = new CDoublyLinkedList();
        CDoublyLinkedList A2 = new CDoublyLinkedList();

        int randomListSize = 20000;
        Random rand = new Random();

        for (int i = 0; i < randomListSize; i++) {
            int x = rand.nextInt(3000000);
            A.addLast(x);
            A2.addLast(x);
        }

        double start = System.currentTimeMillis();
        A.MergeSort();
        double end = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (end - start));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.size());

        start = System.currentTimeMillis();
        A2.InsertionSort();
        end = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (end - start));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.size());
    }
}
