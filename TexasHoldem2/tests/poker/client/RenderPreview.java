package poker.client;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [테스트 / 화면 배치 확인] 가짜 STATE로 실제 TablePanel을 이미지에 그려 본다.
 * JFrame 창을 띄우거나 버튼을 클릭하는 테스트는 아니다.
 * 서버 통신 없이도 카드/좌석의 배치를 확인할 수 있으며 build/table-preview.png로 저장한다.
 */
public final class RenderPreview {
    // 파일 저장까지 마친 뒤 끝나도록 EDT 작업 완료를 기다리는 invokeAndWait를 사용한다.
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                // 게임에서 받을 법한 상태를 수동으로 만든 샘플이다. 실제 게임의 서버 상태는 변경하지 않는다.
                Map<String, String> m = new LinkedHashMap<String, String>();
                m.put("you", "0"); m.put("actor", "0"); m.put("dealer", "2"); m.put("sb", "3"); m.put("bb", "4");
                m.put("hand", "3"); m.put("phase", "FLOP"); m.put("pot", "240"); m.put("board", "AS,TH,7D");
                String[] names = {"플레이어", "민수", "지연", "서준", "하늘", "수빈"};
                for (int i = 0; i < 6; i++) {
                    String p = "p" + i + ".";
                    m.put(p + "name", names[i]); m.put(p + "chips", "960"); m.put(p + "status", "참가");
                    m.put(p + "inHand", "true"); m.put(p + "bet", i == 0 ? "20" : "40"); m.put(p + "cards", i == 0 ? "AH,KC" : "");
                }
                // 실제 UI 부품에 크기와 상태를 주고 화면 대신 BufferedImage에 그린다.
                TablePanel panel = new TablePanel(); panel.setSize(1150, 510); panel.setState(m);
                BufferedImage image = new BufferedImage(1150, 510, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
                // 인자가 있으면 그 경로에, 없으면 build/table-preview.png에 PNG 파일을 저장한다.
                ImageIO.write(image, "png", new File(args.length > 0 ? args[0] : "build/table-preview.png"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("PASS: Swing table rendered offscreen.");
    }
}
