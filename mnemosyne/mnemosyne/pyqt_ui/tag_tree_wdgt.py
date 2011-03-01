#
# tag_tree_wdgt.py <Peter.Bienstman@UGent.be>
#

from PyQt4 import QtCore, QtGui

from mnemosyne.libmnemosyne.translator import _
from mnemosyne.libmnemosyne.tag_tree import TagTree
from mnemosyne.libmnemosyne.component import Component
from mnemosyne.libmnemosyne.criteria.default_criterion import DefaultCriterion

# We hijack QTreeWidgetItem a bit and store extra data in a hidden column, so
# that we don't need to implement a custom tree model.

DISPLAY_STRING = 0
NODE = 1

class TagDelegate(QtGui.QStyledItemDelegate):

    rename_node = QtCore.pyqtSignal(unicode, unicode)   
    redraw_node = QtCore.pyqtSignal(unicode)
    
    def __init__(self, component_manager, parent=None):
        QtGui.QStyledItemDelegate.__init__(self, parent)
        self.old_node_label = None

    def createEditor(self, parent, option, index):
        
        # Ideally, we want to capture the focusOut event here, to redraw the
        # card counts in case of an aborted edit by the user. One option to
        # achieve this is connecting the editingFinished signal instead of
        # returnPressed, but there is a long-standing bug in Qt causing this
        # signal to be emitted twice, with the second call sometimes coming
        # when the first one has not finished yet, which can cause crashes.
        # The other option is to reimplement focusOutEvent of the editor, but
        # that does not seem to work here easily in the context of Delegates.
        # Presumably subclassing QLineEdit would work, though, but at the cost
        # of added complexity.
        #
        # See also:
        #
        #  http://www.qtforum.org/article/33631/qlineedit-the-signal-editingfinished-is-emitted-twice.html
        #  http://bugreports.qt.nokia.com/browse/QTBUG-40
        
        editor = QtGui.QStyledItemDelegate.createEditor\
            (self, parent, option, index)        
        editor.returnPressed.connect(self.commit_and_close_editor)
        return editor

    def setEditorData(self, editor, index):
        # We display the full node (i.e. all levels including ::), so that
        # the hierarchy can be changed upon editing.
        node_index = index.model().index(index.row(), NODE, index.parent())
        self.old_node_label = index.model().data(node_index).toString()
        editor.setText(self.old_node_label)
        
    def commit_and_close_editor(self):
        editor = self.sender()
        if unicode(self.old_node_label) == unicode(editor.text()):
            self.redraw_node.emit(self.old_node_label)
        else:
            self.rename_node.emit(self.old_node_label, editor.text())
        self.closeEditor.emit(editor, QtGui.QAbstractItemDelegate.NoHint)


class TagsTreeWdgt(QtGui.QWidget, Component):

    """Displays all the tags in a tree together with check boxes.
    
    If 'before_using_libmnemosyne_db_hook' and 'after_using_libmnemosyne_db'
    are set, these will be called before and after using libmnemosyne
    operations which can modify the database.

    Typical use case for this comes from a parent widget like the card
    browser, which needs to relinquish its control over the sqlite database
    first, before the tag tree operations can take place.
    
    """

    def __init__(self, component_manager, parent,
            before_using_libmnemosyne_db_hook=None,
            after_using_libmnemosyne_db_hook=None):
        Component.__init__(self, component_manager)
        self.tag_tree = TagTree(self.component_manager)
        QtGui.QWidget.__init__(self, parent)
        self.before_using_libmnemosyne_db_hook = \
            before_using_libmnemosyne_db_hook
        self.after_using_libmnemosyne_db_hook = \
            after_using_libmnemosyne_db_hook
        self.layout = QtGui.QVBoxLayout(self)
        self.tag_tree_wdgt = QtGui.QTreeWidget(self)
        self.tag_tree_wdgt.setColumnCount(2)
        self.tag_tree_wdgt.setColumnHidden(1, True)
        self.tag_tree_wdgt.setColumnHidden(NODE, True)        
        self.tag_tree_wdgt.setHeaderHidden(True)
        self.tag_tree_wdgt.setSelectionMode(\
            QtGui.QAbstractItemView.ExtendedSelection)
        self.delegate = TagDelegate(component_manager, self)
        self.tag_tree_wdgt.setItemDelegate(self.delegate)
        self.delegate.rename_node.connect(self.rename_node)
        self.delegate.redraw_node.connect(self.redraw_node)
        self.layout.addWidget(self.tag_tree_wdgt)
        self.tag_tree_wdgt.setContextMenuPolicy(QtCore.Qt.CustomContextMenu)
        self.tag_tree_wdgt.customContextMenuRequested.connect(\
            self.context_menu)

    def selected_non_read_only_indexes(self):
        indexes = []
        for index in self.tag_tree_wdgt.selectedIndexes():
            node_index = \
                index.model().index(index.row(), NODE, index.parent())
            if index.model().data(node_index).toString() not in \
                ["__ALL__", "__UNTAGGED__"]:
                indexes.append(index)
        return indexes

    def context_menu(self, point):
        menu = QtGui.QMenu(self)
        rename_tag_action = QtGui.QAction(_("Re&name"), menu)
        rename_tag_action.triggered.connect(self.menu_rename)
        rename_tag_action.setShortcut(QtCore.Qt.Key_Enter)
        menu.addAction(rename_tag_action)
        remove_tag_action = QtGui.QAction(_("Re&move"), menu)
        remove_tag_action.triggered.connect(self.menu_remove)
        remove_tag_action.setShortcut(QtGui.QKeySequence.Delete)
        menu.addAction(remove_tag_action)
        indexes = self.selected_non_read_only_indexes()
        if len(indexes) > 1:
            rename_tag_action.setEnabled(False)
        if len(indexes) >= 1:
            menu.exec_(self.tag_tree_wdgt.mapToGlobal(point))

    def keyPressEvent(self, event):
        if event.key() in [QtCore.Qt.Key_Enter, QtCore.Qt.Key_Return]:
            self.menu_rename()
        elif event.key() in [QtCore.Qt.Key_Delete, QtCore.Qt.Key_Backspace]:
            self.menu_remove()

    def menu_rename(self):
        indexes = self.selected_non_read_only_indexes()
        # If there are tags selected, this means that we could only have got
        # after pressing return on an actual edit, due to our custom
        # 'keyPressEvent'. We should not continue in that case.
        if len(indexes) == 0:
            return
        # We display the full node (i.e. all levels including ::), so that
        # the hierarchy can be changed upon editing.
        index = indexes[0]        
        node_index = index.model().index(index.row(), NODE, index.parent())
        old_node_label = index.model().data(node_index).toString()

        from mnemosyne.pyqt_ui.ui_rename_tag_dlg import Ui_RenameTagDlg        
        class RenameDlg(QtGui.QDialog, Ui_RenameTagDlg):          
            def __init__(self, old_node_label):
                QtGui.QDialog.__init__(self)
                self.setupUi(self)
                self.tag_name.setText(old_node_label)

        dlg = RenameDlg(old_node_label)  
        if dlg.exec_() == QtGui.QDialog.Accepted:
            self.rename_node(old_node_label, unicode(dlg.tag_name.text()))
        
    def menu_remove(self):
        # Ask for confirmation.
        indexes = self.selected_non_read_only_indexes()
        if len(indexes) > 1:
            question = _("Remove these tags?")
        else:
            question = _("Remove this tag?")            
        answer = self.main_widget().show_question\
            (question, _("&OK"), _("&Cancel"), "")
        if answer == 1: # Cancel.
            return
        # Remove the nodes.
        node_labels = []
        for index in self.selected_non_read_only_indexes():
            node_index = index.model().index(index.row(), NODE, index.parent())
            node_labels.append(index.model().data(node_index).toString())
        self.delete_nodes(node_labels)
        
    def create_tree(self, tree, qt_parent):
        for node in tree:
            node_name = "%s (%d)" % \
                (self.tag_tree.display_name_for_node[node],
                self.tag_tree.card_count_for_node[node])
            node_item = QtGui.QTreeWidgetItem(qt_parent, [node_name, node], 0)
            node_item.setFlags(node_item.flags() | \
                QtCore.Qt.ItemIsUserCheckable | QtCore.Qt.ItemIsTristate)
            if node not in ["__ALL__", "__UNTAGGED__"]:
                node_item.setFlags(node_item.flags() | \
                    QtCore.Qt.ItemIsEditable)
            if node in self.tag_tree.tag_for_node:
                self.tag_for_node_item[node_item] = \
                    self.tag_tree.tag_for_node[node]
            node_item.setData(NODE, QtCore.Qt.DisplayRole,
                    QtCore.QVariant(QtCore.QString(node)))              
            self.create_tree(tree=self.tag_tree[node], qt_parent=node_item)
        
    def display(self, criterion=None):
        # Create criterion if needed.
        if criterion is None:
            criterion = DefaultCriterion(self.component_manager)
            for tag in self.database().tags():
                criterion.active_tag__ids.add(tag._id)            
        # Create tree.
        self.tag_tree_wdgt.clear()
        self.tag_for_node_item = {}
        node = "__ALL__"
        node_name = "%s (%d)" % (self.tag_tree.display_name_for_node[node],
            self.tag_tree.card_count_for_node[node])
        root = self.tag_tree[node]
        root_item = QtGui.QTreeWidgetItem(\
            self.tag_tree_wdgt, [node_name, node], 0)
        root_item.setFlags(root_item.flags() | \
           QtCore.Qt.ItemIsUserCheckable | QtCore.Qt.ItemIsTristate)
        root_item.setCheckState(0, QtCore.Qt.Checked)
        self.create_tree(self.tag_tree[node], qt_parent=root_item)
        # Set forbidden tags.
        if len(criterion.forbidden_tag__ids):
            for node_item, tag in self.tag_for_node_item.iteritems():
                if tag._id in criterion.forbidden_tag__ids:
                    node_item.setCheckState(0, QtCore.Qt.Checked)
                else:
                    node_item.setCheckState(0, QtCore.Qt.Unchecked)  
        # Set active tags.
        else:
            for node_item, tag in self.tag_for_node_item.iteritems():
                if tag._id in criterion.active_tag__ids:
                    node_item.setCheckState(0, QtCore.Qt.Checked)
                else:
                    node_item.setCheckState(0, QtCore.Qt.Unchecked)
        # Finalise.
        self.tag_tree_wdgt.expandAll()

    def checked_to_active_tags_in_criterion(self, criterion):
        for item, tag in self.tag_for_node_item.iteritems():
            if item.checkState(0) == QtCore.Qt.Checked:
                criterion.active_tag__ids.add(tag._id)
        criterion.forbidden_tags = set()
        return criterion

    def checked_to_forbidden_tags_in_criterion(self, criterion):
        for item, tag in self.tag_for_node_item.iteritems():
            if item.checkState(0) == QtCore.Qt.Checked:
                criterion.forbidden_tag__ids.add(tag._id)
        criterion.active_tags = set(self.tag_for_node_item.values())
        return criterion

    def save_criterion(self):
        self.saved_criterion = DefaultCriterion(self.component_manager)
        self.checked_to_active_tags_in_criterion(self.saved_criterion)
        # Now we've saved the checked state of the tree.
        # Saving and restoring the selected state is less trivial, because
        # in the case of trees, the model indexes have parents which become
        # invalid when creating the widget.
        # The solution would be to save tags and reselect those in the new
        # widget.

    def restore_criterion(self):
        new_criterion = DefaultCriterion(self.component_manager)
        for tag in self.database().tags():
            if tag._id in self.saved_criterion.active_tag__ids:
                new_criterion.active_tag__ids.add(tag._id)  
        self.display(new_criterion)
        
    def hibernate(self):

        """Save the current criterion and unload the database so that
        we can call libmnemosyne functions.

        """

        self.save_criterion()
        if self.before_using_libmnemosyne_db_hook:
            self.before_using_libmnemosyne_db_hook()

    def wakeup(self):

        """Restore the saved criterion and reload the database after
        calling libmnemosyne functions.

        """
        
        self.restore_criterion()
        if self.after_using_libmnemosyne_db_hook:
            self.after_using_libmnemosyne_db_hook()

    def rename_node(self, old_node_label, new_node_label):
        self.hibernate()  
        self.tag_tree.rename_node(\
            unicode(old_node_label), unicode(new_node_label))
        self.wakeup()

    def delete_nodes(self, nodes):
        self.hibernate()
        for node in nodes:
            self.tag_tree.delete_subtree(unicode(node)) 
        self.wakeup()
        
    def redraw_node(self, node_label):

        """When renaming a tag to the same name, we need to redraw the node
        to show the card count again.

        """
        
        # We do the redrawing in a rather hackish way now, simply by
        # recreating the widget. Could be sped up, but at the expense of more
        # complicated code.
        self.save_criterion()
        self.restore_criterion()

    def rebuild(self):

        """To be called when external events invalidate the tag tree,
        e.g. due to edits in the card browser widget.

        """

        self.hibernate()  
        self.tag_tree = TagTree(self.component_manager)
        self.wakeup()