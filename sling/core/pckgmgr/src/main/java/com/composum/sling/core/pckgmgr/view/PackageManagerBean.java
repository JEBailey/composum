package com.composum.sling.core.pckgmgr.view;

import com.composum.sling.nodes.console.ConsoleSlingBean;
import com.composum.sling.core.filter.StringFilter;
import com.composum.sling.core.pckgmgr.util.PackageUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

public class PackageManagerBean extends ConsoleSlingBean {

    private static final Logger LOG = LoggerFactory.getLogger(PackageManagerBean.class);

    private transient String path;
    private transient PackageUtil.TreeType type;
    private transient String downloadUrl;

    @Override
    public String getPath() {
        if (path == null) {
            path = PackageUtil.getPath(getRequest());
        }
        return path;
    }

    public String getViewType() {
        if (type == null) {
            type = PackageUtil.getTreeType(getRequest(), getPath());
        }
        return type != null ? type.name() : "";
    }

    public String getTabType() {
        String selector = getRequest().getSelectors(new StringFilter.BlackList("^tab$"));
        return StringUtils.isNotBlank(selector) ? selector.substring(1) : "general";
    }

    public List<PackageUtil.PackageItem> getCurrentGroupPackages() {
        List<PackageUtil.PackageItem> items = new ArrayList<PackageUtil.PackageItem>();
        try {
            PackageUtil.TreeNode treeNode = PackageUtil.getTreeNode(request);
            for (PackageUtil.TreeItem item : treeNode) {
                if (item instanceof PackageUtil.PackageItem) {
                    if (((PackageUtil.PackageItem) item).getDefinition() != null) {
                        items.add((PackageUtil.PackageItem) item);
                    }
                }
            }
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return items;
    }
}
