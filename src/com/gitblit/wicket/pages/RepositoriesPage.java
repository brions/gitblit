package com.gitblit.wicket.pages;

import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.apache.wicket.PageParameters;
import org.apache.wicket.extensions.markup.html.repeater.data.sort.OrderByBorder;
import org.apache.wicket.extensions.markup.html.repeater.util.SortParam;
import org.apache.wicket.extensions.markup.html.repeater.util.SortableDataProvider;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.link.BookmarkablePageLink;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.markup.repeater.data.DataView;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;

import com.gitblit.GitBlit;
import com.gitblit.StoredSettings;
import com.gitblit.utils.Utils;
import com.gitblit.wicket.BasePage;
import com.gitblit.wicket.GitBlitWebSession;
import com.gitblit.wicket.LinkPanel;
import com.gitblit.wicket.WicketUtils;
import com.gitblit.wicket.models.RepositoryModel;


public class RepositoriesPage extends BasePage {

	public RepositoriesPage() {
		super();
		setupPage("", "");
		
		boolean showAdmin = false;
		if (StoredSettings.getBoolean("authenticateWebUI", true)) {
			boolean allowAdmin = StoredSettings.getBoolean("allowAdministration", false);
			showAdmin = allowAdmin && GitBlitWebSession.get().canAdmin(); 
		} else {
			showAdmin = StoredSettings.getBoolean("allowAdministration", false);
		}
		
		Fragment adminLinks = new Fragment("adminPanel", "adminLinks", this);
		adminLinks.add(new BookmarkablePageLink<Void>("newRepository", RepositoriesPage.class));
		adminLinks.add(new BookmarkablePageLink<Void>("newUser", RepositoriesPage.class));		
		add(adminLinks.setVisible(showAdmin));
		
		add(new Label("repositoriesMessage", StoredSettings.getString("repositoriesMessage", "")).setEscapeModelStrings(false));

		List<RepositoryModel> rows = GitBlit.self().getRepositories(getRequest());
		DataProvider dp = new DataProvider(rows);
		DataView<RepositoryModel> dataView = new DataView<RepositoryModel>("repository", dp) {
			private static final long serialVersionUID = 1L;
			int counter = 0;

			public void populateItem(final Item<RepositoryModel> item) {
				final RepositoryModel entry = item.getModelObject();
				PageParameters pp = WicketUtils.newRepositoryParameter(entry.name);
				item.add(new LinkPanel("repositoryName", "list", entry.name, SummaryPage.class, pp));
				item.add(new LinkPanel("repositoryDescription", "list", entry.description, SummaryPage.class, pp));
				item.add(new Label("repositoryOwner", entry.owner));

				String lastChange = Utils.timeAgo(entry.lastChange);
				Label lastChangeLabel = new Label("repositoryLastChange", lastChange);
				item.add(lastChangeLabel);
				WicketUtils.setCssClass(lastChangeLabel, Utils.timeAgoCss(entry.lastChange));

				WicketUtils.setAlternatingBackground(item, counter);
				counter++;
			}
		};
		add(dataView);

		add(newSort("orderByRepository", SortBy.repository, dp, dataView));
		add(newSort("orderByDescription", SortBy.description, dp, dataView));
		add(newSort("orderByOwner", SortBy.owner, dp, dataView));
		add(newSort("orderByDate", SortBy.date, dp, dataView));
	}

	protected enum SortBy {
		repository, description, owner, date;
	}

	protected OrderByBorder newSort(String wicketId, SortBy field, SortableDataProvider<?> dp, final DataView<?> dataView) {
		return new OrderByBorder(wicketId, field.name(), dp) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void onSortChanged() {
				dataView.setCurrentPage(0);
			}
		};
	}

	private class DataProvider extends SortableDataProvider<RepositoryModel> {
		private static final long serialVersionUID = 1L;
		private List<RepositoryModel> list = null;

		protected DataProvider(List<RepositoryModel> list) {
			this.list = list;
			setSort(SortBy.date.name(), false);
		}

		@Override
		public int size() {
			if (list == null)
				return 0;
			return list.size();
		}

		@Override
		public IModel<RepositoryModel> model(RepositoryModel header) {
			return new Model<RepositoryModel>(header);
		}

		@Override
		public Iterator<RepositoryModel> iterator(int first, int count) {
			SortParam sp = getSort();
			String prop = sp.getProperty();
			final boolean asc = sp.isAscending();

			if (prop == null || prop.equals(SortBy.date.name())) {
				Collections.sort(list, new Comparator<RepositoryModel>() {
					@Override
					public int compare(RepositoryModel o1, RepositoryModel o2) {
						if (asc)
							return o1.lastChange.compareTo(o2.lastChange);
						return o2.lastChange.compareTo(o1.lastChange);
					}
				});
			} else if (prop.equals(SortBy.repository.name())) {
				Collections.sort(list, new Comparator<RepositoryModel>() {
					@Override
					public int compare(RepositoryModel o1, RepositoryModel o2) {
						if (asc)
							return o1.name.compareTo(o2.name);
						return o2.name.compareTo(o1.name);
					}
				});
			} else if (prop.equals(SortBy.owner.name())) {
				Collections.sort(list, new Comparator<RepositoryModel>() {
					@Override
					public int compare(RepositoryModel o1, RepositoryModel o2) {
						if (asc)
							return o1.owner.compareTo(o2.owner);
						return o2.owner.compareTo(o1.owner);
					}
				});
			} else if (prop.equals(SortBy.description.name())) {
				Collections.sort(list, new Comparator<RepositoryModel>() {
					@Override
					public int compare(RepositoryModel o1, RepositoryModel o2) {
						if (asc)
							return o1.description.compareTo(o2.description);
						return o2.description.compareTo(o1.description);
					}
				});
			}
			return list.subList(first, first + count).iterator();
		}
	}
}
