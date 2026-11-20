import java.util.Random;

public class Tester {

    public static void main(String[] args) {

        LinkedList A  = new LinkedList();
        LinkedList A2 = new LinkedList();

        int randomListSize = 20000;

        Random random = new Random();
        for (int i = 0; i < randomListSize; i++) {
            int randomInt = random.nextInt(3000001);
            A.addLast(randomInt);
            A2.addLast(randomInt);
        }

        double before = System.currentTimeMillis();
        A.MergeSort();
        double after = System.currentTimeMillis();
        System.out.println("Time for mergesort " + (after - before));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + A.getSize());


        before = System.currentTimeMillis();
        A2.InsertionSort();
        after = System.currentTimeMillis();
        System.out.println("Time for insertionsort " + (after - before));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + A2.getSize());

    }
}
