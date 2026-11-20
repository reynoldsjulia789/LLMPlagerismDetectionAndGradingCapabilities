import java.util.Random;
public class Tester {
    public static void main(String[] args) {
        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();
        int ramdomListSize = 20000;
        for(int i = 0; i < ramdomListSize; i++) {
            int randomInt = (int)(Math.random() * 3000000);
            A.addLast(new ListNode(randomInt));
            A2.addLast(new ListNode(randomInt));
        }
//measure the time cost of merge sort
        double then = System.currentTimeMillis();
        A.mergeSort();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (now - then));
        System.out.println(A.isSorted()); //verify that your merge sort implementation works.
        System.out.println("Size of list A is: Not actually on the list of requirements :p");
//measure the time cost of insertion sort
        then = System.currentTimeMillis();
        A2.insertionSort();
        now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (now - then));
        System.out.println(A2.isSorted() ); //verify that your insertion sort works.
        System.out.println("Size of list A2 is: Not actually on the list of requirements :p");
    }//end of main
}//end of tester class

