import java.util.HashSet;
import java.util.Set;

public class ChatGroup {
    public String name;
    public String creator;
    public Set<String> members = new HashSet<>();

    public ChatGroup(String name, String creator) {
        this.name = name;
        this.creator = creator;
    }

    public void addMember(String username) {
        if (username != null && !username.trim().isEmpty()) {
            members.add(username);
        }
    }

    public void removeMember(String username) {
        members.remove(username);
    }

    public boolean isMember(String username) {
        return members.contains(username);
    }

    public int getMemberCount() {
        return members.size();
    }

    public String getMembersAsString() {
        return String.join(",", members);
    }

    public boolean isCreator(String username) {
        return creator.equals(username);
    }

    @Override
    public String toString() {
        return "ChatGroup{" +
                "name='" + name + '\'' +
                ", creator='" + creator + '\'' +
                ", members=" + members.size() +
                '}';
    }
}