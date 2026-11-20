import java.util.Random;

public class Tester {
    public static void main(String[] args) {
        LinkedList A  = new LinkedList();
        LinkedList A2 = new LinkedList();

        int randomListSize = 20000;

        for(int i = 0; i < randomListSize; i++) {
            int randomInt = (int) (Math.random() * 3000000);
            A.addLast(randomInt);
            A2.addLast(randomInt);
        }
        double then = System.currentTimeMillis();
        A.MergeSort();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for MergeSort " + (now - then));
        System.out.println(A.isSorted());
        System.out.println("Size of List A is: " + A.getSize());

        then = System.currentTimeMillis();
        A2.InsertionSort();
        now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for InsertionSort " + (now - then));
        System.out.println(A2.isSorted());
        System.out.println("Size of List A2 is: " + A2.getSize());
    } // End Main
} // End class