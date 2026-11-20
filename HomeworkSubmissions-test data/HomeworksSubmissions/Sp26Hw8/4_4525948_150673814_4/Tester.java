import java.util.Random;

public class Tester {

    public static void main(String[] args) {

        LinkedList A = new LinkedList();
        LinkedList A2 = new LinkedList();

        Random rand = new Random();

        for(int i = 0; i < 20000; i++) {
            int value = rand.nextInt(3000001);

            A.addLast(value);
            A2.addLast(value);
        }

        long start = System.currentTimeMillis();

        A.MergeSort();

        long end = System.currentTimeMillis();

        System.out.println("MergeSort Time = "
                + (end - start) + " ms");

        System.out.println("MergeSort Sorted? "
                + A.isSorted());

        start = System.currentTimeMillis();

        A2.InsertionSort();

        end = System.currentTimeMillis();

        System.out.println("InsertionSort Time = "
                + (end - start) + " ms");

        System.out.println("InsertionSort Sorted? "
                + A2.isSorted());
    }
}