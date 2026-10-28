package poker.client;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Headless render check of the actual Swing table component. */
public final class RenderPreview {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Map<String, String> m = new LinkedHashMap<String, String>();
                m.put("you", "0"); m.put("actor", "0"); m.put("dealer", "2"); m.put("sb", "3"); m.put("bb", "4");
                m.put("hand", "3"); m.put("phase", "FLOP"); m.put("pot", "240"); m.put("board", "AS,TH,7D");
                String[] names = {"플레이어", "민수", "지연", "서준", "하늘", "수빈"};
                for (int i = 0; i < 6; i++) {
                    String p = "p" + i + ".";
                    m.put(p + "name", names[i]); m.put(p + "chips", "960"); m.put(p + "status", "참가");
                    m.put(p + "inHand", "true"); m.put(p + "bet", i == 0 ? "20" : "40"); m.put(p + "cards", i == 0 ? "AH,KC" : "");
                }
                TablePanel panel = new TablePanel(); panel.setSize(1150, 510); panel.setState(m);
                BufferedImage image = new BufferedImage(1150, 510, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
                ImageIO.write(image, "png", new File(args.length > 0 ? args[0] : "build/table-preview.png"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("PASS: Swing table rendered offscreen.");
    }
}
