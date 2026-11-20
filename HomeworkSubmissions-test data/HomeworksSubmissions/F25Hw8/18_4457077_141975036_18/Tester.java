import java.util.Random;
public class Tester {

    public static void main(String[] args) {
    LinkedList A = new LinkedList();
    LinkedList A2 = new LinkedList();
    Random rand = new Random();
    for(int i = 0; i < 20000; i++){
        int randomInt = rand.nextInt(3000000);
        A.addLast(randomInt);
        A2.addLast(randomInt);
    }

    double then = System.currentTimeMillis();
    A.MergeSort();
    double now = System.currentTimeMillis();
    System.out.println("Time costs in milliseconds for mergesort " + (now - then));
    System.out.println(A.isSorted());
    System.out.println("Size of list A is " + A.getSize());

    then = System.currentTimeMillis();
    A2.InsertionSort();
    now = System.currentTimeMillis();
    System.out.println("Time costs in milliseconds for insertionsort " + (now - then));
    System.out.println(A2.isSorted());
    System.out.println("Size of list A2 is " + A2.getSize());

    }
}