import com.yypm.assistant.Board;
public class Diag {
    public static void main(String[] a) {
        String real = "3akab2/1r1n5/4b1n2/p1p1p1p1p/9/3R2P2/P1P1P1RCP/3RC1N2/4N4/2BAKAB2 w";
        System.out.println("validate = " + Board.validate(real));
        System.out.println("rows = " + real.split("/").length);
        char[][] b = Board.fromFen(real);
        for (int r = 0; r < 10; r++) {
            StringBuilder s = new StringBuilder();
            for (int c = 0; c < 9; c++) s.append(b[r][c] == 0 ? '.' : b[r][c]);
            System.out.println(r + ": " + s);
        }
    }
}
