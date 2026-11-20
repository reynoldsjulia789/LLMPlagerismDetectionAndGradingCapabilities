import java.util.Random;

public class Tester {
    public static void main(String[] Args) {
        Random r = new Random();
        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();

        for(int i = 0; i < 20000; i++) {
            Integer randInt = r.nextInt( 0, 3000000);
            A.addFirst(randInt);
            A2.addFirst(randInt);
        }

        //measure the time cost of merge sort
        double then = System.currentTimeMillis();
        A.mergeSort();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (now - then));
        System.out.println(A.isSorted()); //verify that your merge sort implementation works.
        System.out.println("Size of list A is: " + A.getSize());
        //measure the time cost of insertion sort
        then = System.currentTimeMillis();
        A2.insertionSort();
        now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (now - then));
        System.out.println(A2.isSorted() ); //verify that your insertion sort works.
        System.out.println("Size of list A2 is: " + A2.getSize());
    }
}
