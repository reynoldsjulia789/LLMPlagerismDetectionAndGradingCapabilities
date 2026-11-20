import java.util.Random;

public class Tester{
    
    public static void main(String args[]){
        LinkedList<Integer> A = new LinkedList<Integer>();
        LinkedList<Integer> A2 = new LinkedList<Integer>();
        
        int randomListSize = 20000;
        for(int i = 0; i < randomListSize; i++){
            Integer randomInt = (int)(Math.random() *3000000);
            A.addLast(randomInt);
            A2.addLast(randomInt);
        }

        double then = System.currentTimeMillis();
        A.MergeSort();
        double now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for mergesort " + (now - then));
        System.out.println(A.isSorted());
        System.out.println("Size of list A is: " + randomListSize);
        
        then = System.currentTimeMillis();
        A2.InsertionSort();
        now = System.currentTimeMillis();
        System.out.println("Time cost in milliseconds for insertionsort " + (now-then));
        System.out.println(A2.isSorted());
        System.out.println("Size of list A2 is: " + randomListSize);
    }

}
