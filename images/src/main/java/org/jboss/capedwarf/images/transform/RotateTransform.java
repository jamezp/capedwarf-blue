/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.capedwarf.images.transform;

import com.google.appengine.api.images.Transform;

import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

/**
 * @author <a href="mailto:marko.luksa@gmail.com">Marko Luksa</a>
 */
public class RotateTransform extends JBossTransform {

    public RotateTransform(Transform transform) {
        super(transform);
    }

    @Override
    public BufferedImage applyTo(BufferedImage image) {
        AffineTransform tx = AffineTransform.getQuadrantRotateInstance(getNumOfQuadrants());
        translateCoordinates(tx, image.getWidth(), image.getHeight());
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(image, null);
    }

    private int getNumOfQuadrants() {
        return getDegrees() / 90;
    }

    private void translateCoordinates(AffineTransform tx, int imageWidth, int imageHeight) {
        switch (getDegrees()) {
            case 90:
                tx.translate(0, -imageHeight);
                break;
            case 180:
                tx.translate(-imageWidth, -imageHeight);
                break;
            case 270:
                tx.translate(-imageWidth, 0);
                break;
        }
    }

    private int getDegrees() {
        return (Integer) getFieldValue("degrees");
    }
}
