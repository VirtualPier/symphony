/*
 * Symphony - A modern community (forum/SNS/blog) platform written in Java.
 * Copyright (C) 2012-2016,  b3log.org & hacpai.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.b3log.symphony.processor;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.RuntimeEnv;
import org.b3log.latke.image.Image;
import org.b3log.latke.image.ImageService;
import org.b3log.latke.image.ImageServiceFactory;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.servlet.HTTPRequestContext;
import org.b3log.latke.servlet.HTTPRequestMethod;
import org.b3log.latke.servlet.annotation.RequestProcessing;
import org.b3log.latke.servlet.annotation.RequestProcessor;
import org.b3log.latke.servlet.renderer.PNGRenderer;
import org.b3log.symphony.SymphonyServletListener;
import org.b3log.symphony.model.Common;
import org.json.JSONObject;

/**
 * Captcha processor.
 *
 * <p>
 * Checkout <a href="http://toy-code.googlecode.com/svn/trunk/CaptchaGenerator">
 * the sample captcha generator</a> for more details.
 * </p>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.2.0.5, Nov 1, 2016
 * @since 0.2.2
 */
@RequestProcessor
public class CaptchaProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(CaptchaProcessor.class.getName());

    /**
     * Images service.
     */
    private static final ImageService IMAGE_SERVICE = ImageServiceFactory.getImageService();

    /**
     * Key of captcha.
     */
    public static final String CAPTCHA = "captcha";

    /**
     * Captchas.
     */
    private Image[] captchas;

    /**
     * Count of static captchas.
     */
    private static final int CAPTCHA_COUNT = 100;

    /**
     * Gets captcha.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/captcha", method = HTTPRequestMethod.GET)
    public void get(final HTTPRequestContext context) {
        final PNGRenderer renderer = new PNGRenderer();

        context.setRenderer(renderer);

        if (null == captchas) {
            loadCaptchas();
        }

        try {
            final HttpServletRequest request = context.getRequest();
            final HttpServletResponse response = context.getResponse();

            final Random random = new Random();
            final int index = random.nextInt(CAPTCHA_COUNT);
            final Image captchaImg = captchas[index];
            final String captcha = captchaImg.getName();

            final HttpSession httpSession = request.getSession(false);

            if (null != httpSession) {
                LOGGER.log(Level.DEBUG, "Captcha[{0}] for session[id={1}]", new Object[]{captcha, httpSession.getId()});
                httpSession.setAttribute(CAPTCHA, captcha);
            }

            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires", 0);

            renderer.setImage(captchaImg);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
        }
    }

    /**
     * Gets captcha for login.
     *
     * @param context the specified context
     */
    @RequestProcessing(value = "/captcha/login", method = HTTPRequestMethod.GET)
    public void getLoginCaptcha(final HTTPRequestContext context) {
        if (null == captchas) {
            loadCaptchas();
        }

        try {
            final HttpServletRequest request = context.getRequest();
            final HttpServletResponse response = context.getResponse();

            final String userId = request.getParameter(Common.NEED_CAPTCHA);
            if (StringUtils.isBlank(userId)) {
                return;
            }

            final JSONObject wrong = LoginProcessor.WRONG_PWD_TRIES.get(userId);
            if (null == wrong) {
                return;
            }

            if (wrong.optInt(Common.WRON_COUNT) < 3) {
                return;
            }

            final PNGRenderer renderer = new PNGRenderer();
            context.setRenderer(renderer);

            final Random random = new Random();
            final int index = random.nextInt(CAPTCHA_COUNT);
            final Image captchaImg = captchas[index];
            final String captcha = captchaImg.getName();
            wrong.put(CAPTCHA, captcha);

            response.setHeader("Pragma", "no-cache");
            response.setHeader("Cache-Control", "no-cache");
            response.setDateHeader("Expires", 0);

            renderer.setImage(captchaImg);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, e.getMessage(), e);
        }
    }

    /**
     * Loads captcha.
     */
    private synchronized void loadCaptchas() {
        LOGGER.trace("Loading captchas....");

        try {
            captchas = new Image[CAPTCHA_COUNT];

            ZipFile zipFile;

            if (RuntimeEnv.LOCAL == Latkes.getRuntimeEnv()) {
                final InputStream inputStream = SymphonyServletListener.class.getClassLoader().getResourceAsStream("captcha_static.zip");
                final File file = File.createTempFile("b3log_captcha_static", null);
                final OutputStream outputStream = new FileOutputStream(file);

                IOUtils.copy(inputStream, outputStream);
                zipFile = new ZipFile(file);

                IOUtils.closeQuietly(inputStream);
                IOUtils.closeQuietly(outputStream);
            } else {
                final URL captchaURL = SymphonyServletListener.class.getClassLoader().getResource("captcha_static.zip");

                zipFile = new ZipFile(captchaURL.getFile());
            }

            final Enumeration<? extends ZipEntry> entries = zipFile.entries();

            int i = 0;

            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();

                final BufferedInputStream bufferedInputStream = new BufferedInputStream(zipFile.getInputStream(entry));
                final byte[] captchaCharData = new byte[bufferedInputStream.available()];

                bufferedInputStream.read(captchaCharData);
                bufferedInputStream.close();

                final Image image = IMAGE_SERVICE.makeImage(captchaCharData);

                image.setName(entry.getName().substring(0, entry.getName().lastIndexOf('.')));

                captchas[i] = image;

                i++;
            }

            zipFile.close();
        } catch (final Exception e) {
            LOGGER.error("Can not load captchs!");

            throw new IllegalStateException(e);
        }

        LOGGER.trace("Loaded captch images");
    }
}
